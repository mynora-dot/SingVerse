package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

/** Each operation uses an expendable JVM; a guest infinite loop cannot hold the web JVM. */
@Component
public class LxRuntime {
    private final ObjectMapper json=new ObjectMapper();
    private final LxNetwork network;
    private final Semaphore slots=new Semaphore(3,true);
    private final Set<Process> children=ConcurrentHashMap.newKeySet();
    private final ExecutorService readers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"lx-ipc");t.setDaemon(true);return t;});
    private final ExecutorService http=Executors.newFixedThreadPool(8,r->{var t=new Thread(r,"lx-http");t.setDaemon(true);return t;});
    public LxRuntime(LxNetwork network) {this.network=network;}
    public Map<String,List<String>> inspect(String script,String environment) {return operate(script,environment,null).capabilities;}
    public String resolve(String script,String environment,Track track,String quality) {
        return operate(script,environment,args("kind","resolve","platform",track.platform(),"quality",quality,"musicInfo",track.musicInfo())).url;
    }
    private Result operate(String script,String environment,Map<String,Object> resolution) {
        if(!slots.tryAcquire())throw conflict("音源正在处理其他请求，请稍后再试");
        String owner=UUID.randomUUID().toString();var calls=ConcurrentHashMap.<String>newKeySet();
        AtomicReference<Process> process=new AtomicReference<>();
        AtomicBoolean abandoned=new AtomicBoolean();
        CompletableFuture<Map<String,List<String>>> initialized=new CompletableFuture<>();
        CompletableFuture<String> result=new CompletableFuture<>();
        CompletableFuture<BufferedWriter> writer=new CompletableFuture<>();
        Future<?> io=readers.submit(()->{
            try {
                Process child=startWorker();process.set(child);children.add(child);
                if(abandoned.get()){child.destroyForcibly();children.remove(child);return;}
                var output=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));writer.complete(output);
                send(output,args("kind","load","script",script,"environment",environment));
                try(var input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while((line=readLine(input,12*1024*1024))!=null) {
                        JsonNode frame=json.readTree(line);String kind=frame.path("kind").asText();
                        if(kind.equals("inited"))initialized.complete(capabilities(frame.path("data")));
                        else if(kind.equals("failure"))throw new IllegalArgumentException(sourceFailure(frame.path("message").asText()));
                        else if(kind.equals("result")) {
                            if(!initialized.isDone()||resolution==null)continue;
                            String url=frame.path("url").asText("");
                            if(url.length()>2048)throw new IllegalArgumentException("音源返回的地址过长");
                            LxNetwork.checked(url,false);result.complete(url);break;
                        } else if(kind.equals("http")) {
                            if(calls.size()>=32)throw new IllegalArgumentException("音源并发请求过多");
                            String requestOwner=owner+":"+frame.path("id").asInt();
                            if(!frame.path("id").canConvertToInt()||frame.path("id").asInt()<1||!calls.add(requestOwner))
                                throw new IllegalArgumentException("音源请求标识无效，请检查脚本兼容性");
                            http.submit(()->{
                                Map<String,Object> response;
                                try {
                                    var headers=new LinkedHashMap<String,String>();
                                    frame.path("headers").fields().forEachRemaining(e->{if(e.getValue().isValueNode())headers.put(e.getKey(),e.getValue().asText());});
                                    byte[] body=frame.path("body").isNull()?null:frame.path("binary").asBoolean()?Base64.getDecoder().decode(frame.path("body").asText()):frame.path("body").asText().getBytes(StandardCharsets.UTF_8);
                                    if(body!=null&&body.length>8*1024*1024)throw new IllegalArgumentException();
                                    if(!Set.of("GET","POST","PUT","DELETE","PATCH","HEAD","OPTIONS").contains(frame.path("method").asText()))throw new IllegalArgumentException();
                                    if(!child.isAlive())return;
                                    var reply=network.request(frame.path("url").asText(),frame.path("method").asText(),headers,body,8*1024*1024,false,requestOwner);
                                    response=args("kind","httpReply","id",frame.path("id").asInt(),"status",reply.status(),"headers",reply.headers(),"body",Base64.getEncoder().encodeToString(reply.bytes()),"url",reply.url());
                                }catch(Exception error) {response=args("kind","httpReply","id",frame.path("id").asInt(),"error","音源网络请求失败，请检查音源和网络");}
                                finally {calls.remove(requestOwner);}
                                try {if(child.isAlive())send(output,response);}catch(Exception ignored){}
                            });
                        } else if(kind.equals("cancelHttp"))network.cancel(owner+":"+frame.path("id").asInt());
                    }
                }
                if(!result.isDone())throw new IllegalArgumentException("音源进程提前结束，请检查脚本兼容性");
            }catch(Throwable error) {initialized.completeExceptionally(error);result.completeExceptionally(error);writer.completeExceptionally(error);}
        });
        try {
            Map<String,List<String>> capabilities=initialized.get(10,TimeUnit.SECONDS);
            if(resolution==null)return new Result(capabilities,null);
            String platform=resolution.get("platform").toString(),quality=resolution.get("quality").toString();
            if(!capabilities.getOrDefault(platform,List.of()).contains(quality))throw bad("当前音源不支持所选平台或音质，请更换音源或选择自动音质");
            send(writer.get(1,TimeUnit.SECONDS),resolution);
            return new Result(capabilities,result.get(20,TimeUnit.SECONDS));
        }catch(TimeoutException error) {throw new LxFailure("音源响应超时，请重试或更换音源");}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new LxFailure("解析已中断，可重新尝试");}
        catch(ExecutionException error) {throw new LxFailure(clean(error.getCause().getMessage()));}
        catch(IOException error) {throw new LxFailure("音源进程通信失败，请重试");}
        finally {
            abandoned.set(true);Process child=process.get();if(child!=null){child.destroyForcibly();children.remove(child);}
            calls.forEach(network::cancel);io.cancel(true);slots.release();
        }
    }
    static Map<String,List<String>> capabilities(JsonNode data) {
        var result=new LinkedHashMap<String,List<String>>();
        for(String platform:PLATFORMS.keySet()) {
            JsonNode source=data.path("sources").path(platform);boolean action=false;
            for(var item:source.path("actions"))if(item.asText().equals("musicUrl"))action=true;
            var supported=new ArrayList<String>();
            for(String quality:QUALITIES)for(var value:source.path("qualitys"))if(value.asText().equals(quality)&&!supported.contains(quality))supported.add(quality);
            if(action&&!supported.isEmpty())result.put(platform,List.copyOf(supported));
        }
        if(result.isEmpty())throw bad("音源没有声明可用的歌曲平台和音质，请导入支持 LX 2.0 的音源");
        return result;
    }
    private Process startWorker() throws IOException {
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-Xss1m","-Dfile.encoding=UTF-8"));
        File source=new ApplicationHome(com.mynora.singverse.SingVerseApplication.class).getSource();
        if(source!=null&&source.isFile()&&source.getName().endsWith(".jar")) {command.add("-jar");command.add(source.getAbsolutePath());}
        else {command.add("-cp");command.add(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")));command.add("com.mynora.singverse.SingVerseApplication");}
        command.add("--lx-worker");
        var builder=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);builder.environment().clear();builder.environment().put("LANG","C.UTF-8");
        return builder.start();
    }
    private void send(BufferedWriter writer,Object frame) throws IOException {synchronized(writer){writer.write(json.writeValueAsString(frame));writer.newLine();writer.flush();}}
    static String readLine(Reader reader,int limit) throws IOException {
        var out=new StringBuilder();int value;while((value=reader.read())!=-1&&value!='\n') {if(out.length()>=limit)throw new IOException("IPC response too large");out.append((char)value);}return value==-1&&out.isEmpty()?null:out.toString();
    }
    private record Result(Map<String,List<String>> capabilities,String url) {}
    public static class LxFailure extends RuntimeException {public LxFailure(String message){super(message);}}
    @PreDestroy public void close(){children.forEach(Process::destroyForcibly);readers.shutdownNow();http.shutdownNow();}
}
