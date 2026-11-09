package com.mynora.singverse.media.download;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Private stdin/stdout protocol. This entry point starts before Spring or database initialization. */
public final class LxWorker {
    private LxWorker() {}
    public static void run() {
        ObjectMapper json=new ObjectMapper();var inbox=new LinkedBlockingQueue<JsonNode>(256);
        var timers=new HashMap<Integer,Timer>();
        Thread reader=new Thread(()->{
            try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                String line;while((line=LxRuntime.readLine(input,64*1024*1024))!=null) {inbox.put(json.readTree(line));}
            }catch(Exception ignored){}finally{inbox.offer(json.createObjectNode().put("kind","stop"));}
        },"lx-input");reader.setDaemon(true);reader.start();
        try(Context context=Context.newBuilder("js").allowHostAccess(HostAccess.NONE).allowHostClassLookup(name->false)
                .allowIO(IOAccess.NONE).allowNativeAccess(false).allowCreateThread(false).allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .option("engine.WarnInterpreterOnly","false").option("js.load","false")
                .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream()).build()) {
            JsonNode load=inbox.take();if(!load.path("kind").asText().equals("load"))return;
            var bindings=context.getBindings("js");
            bindings.putMember("__lx_environment",load.path("environment").asText("mobile"));
            var info=json.createObjectNode();String script=load.path("script").asText();
            for(String key:List.of("name","version","author","description")){var pattern=java.util.regex.Pattern.compile("(?m)^\\s*\\*?\\s*@"+key+"\\s+([^\\r\\n]{1,200})").matcher(script);if(pattern.find())info.put(key,pattern.group(1).replace("*/","").trim());}
            bindings.putMember("__lx_scriptInfo",info.toString());
            bindings.putMember("__lx_tool",(ProxyExecutable)args->LxTools.call(args[0].asString(),args[1].asString()));
            bindings.putMember("__lx_emit",(ProxyExecutable)args->{
                String frame=args[0].asString();if(frame.length()>12*1024*1024)throw new IllegalArgumentException("响应过大");
                try {
                    JsonNode event=json.readTree(frame);String kind=event.path("kind").asText();
                    if(kind.equals("timer")) {
                        if(timers.size()>=64)throw new IllegalArgumentException();
                        int id=event.path("id").asInt();long delay=Math.max(1,event.path("delay").asLong());
                        timers.put(id,new Timer(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(delay),delay,event.path("repeat").asBoolean()));
                    } else if(kind.equals("clearTimer"))timers.remove(event.path("id").asInt());
                    else {System.out.println(frame);System.out.flush();}
                } catch(IOException e) {throw new IllegalArgumentException("音源响应无效");}
                return null;
            });
            String preload;
            try(var input=LxWorker.class.getResourceAsStream("/lx/preload.js")) {preload=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
            Value dispatch=context.eval("js",preload);
            for(String key:List.of("__lx_emit","__lx_tool","__lx_environment","__lx_scriptInfo"))bindings.removeMember(key);
            context.eval(Source.newBuilder("js",load.path("script").asText(),"custom-source.js").buildLiteral());
            while(true) {
                JsonNode event=inbox.poll(10,TimeUnit.MILLISECONDS);
                if(event!=null) {
                    if(event.path("kind").asText().equals("stop"))break;
                    dispatch.execute(context.eval("js","("+event.toString()+")"));
                }
                long now=System.nanoTime();
                for(Integer id:new ArrayList<>(timers.keySet())) {
                    Timer timer=timers.get(id);if(timer==null||timer.at>now)continue;
                    if(timer.repeat)timers.put(id,new Timer(now+TimeUnit.MILLISECONDS.toNanos(timer.delay),timer.delay,true));else timers.remove(id);
                    dispatch.execute(context.eval("js","({kind:'timer',id:"+id+"})"));
                }
            }
        }catch(Throwable error) {
            // Never return stack traces or script bodies over the boundary.
            System.out.println("{\"kind\":\"failure\",\"message\":\"音源执行失败，请检查脚本兼容性\"}");System.out.flush();
        }
    }
    private record Timer(long at,long delay,boolean repeat) {}
}
