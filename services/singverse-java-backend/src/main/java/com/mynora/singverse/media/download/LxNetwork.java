package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Public-network-only HTTP. The validating DNS resolver supplies the actual connection addresses. */
@Component
public class LxNetwork {
    public static final long MAX_AUDIO=512L*1024*1024;
    private final ConcurrentHashMap<String,Call> calls=new ConcurrentHashMap<>();
    private final OkHttpClient client;
    public LxNetwork() {
        client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES)
                .dns(host->{
                    var addresses=Dns.SYSTEM.lookup(host);
                    if(addresses.isEmpty()||addresses.stream().anyMatch(a->!publicAddress(a)))throw new UnknownHostException("非公网目标已拒绝");
                    return addresses;
                }).build();
    }
    LxNetwork(OkHttpClient client) {this.client=client;}
    public record Reply(int status,Map<String,String> headers,byte[] bytes,String url) {}
    public record Download(long size,String hash,String extension) {}
    @FunctionalInterface private interface Reader<T> { T read(Response response) throws Exception; }
    @FunctionalInterface public interface Progress { void update(long received,Long total); }

    public static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()
                ||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] raw=address.getAddress();
        int a=raw[0]&255,b=raw[1]&255,c=raw[2]&255;
        if(raw.length==4) {
            return !(a==0||a==10||a==127||a>=224||a==100&&b>=64&&b<=127||a==169&&b==254
                    ||a==172&&b>=16&&b<=31||a==192&&(b==168||b==0&&c==0||b==0&&c==2||b==88&&c==99)
                    ||a==198&&(b==18||b==19||b==51&&c==100)||a==203&&b==0&&c==113);
        }
        if((a&0xe0)!=0x20)return false;
        if(a==0x20&&b==0x01&&(raw[2]&255)==0x0d&&(raw[3]&255)==0xb8)return false;
        if(a==0x20&&b==0x01&&c==0&&Set.of(0,2,0x10,0x20).contains(raw[3]&255))return false;
        if(a==0x20&&b==0x02) {
            try { return publicAddress(InetAddress.getByAddress(Arrays.copyOfRange(raw,2,6))); }
            catch(UnknownHostException e) { return false; }
        }
        return true;
    }
    public static HttpUrl checked(String value,boolean httpsOnly) {
        HttpUrl url=HttpUrl.parse(value==null?"":value);
        if(url==null||httpsOnly&&!url.isHttps()||!url.username().isEmpty()||!url.password().isEmpty()
                ||url.host().equalsIgnoreCase("localhost")||url.fragment()!=null)throw bad("请输入有效的公网"+(httpsOnly?" HTTPS":" HTTP(S)")+"地址");
        // Numeric addresses can bypass a client's DNS callback; validate them here as well.
        if(url.host().contains(":")||url.host().matches("[0-9.]+")) {
            try { if(!publicAddress(InetAddress.getByName(url.host())))throw bad("该地址不是公网地址"); }
            catch(UnknownHostException e) { throw bad("地址无效"); }
        }
        return url;
    }
    private <T> T perform(String address,String method,Map<String,String> headers,byte[] body,
            boolean httpsOnly,String owner,int seconds,Reader<T> reader) {
        HttpUrl url=checked(address,httpsOnly);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        var safeHeaders=new LinkedHashMap<>(headers);
        for(int hop=0;hop<=5;hop++) {
            Request.Builder request=new Request.Builder().url(url).header("User-Agent","SingVerse LX/1.0");
            safeHeaders.forEach((key,value)->{
                if(!Set.of("host","connection","content-length","proxy-authorization","transfer-encoding").contains(key.toLowerCase(Locale.ROOT)))
                    request.header(key,value);
            });
            String contentType=safeHeaders.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Content-Type")).map(Map.Entry::getValue).findFirst().orElse("application/octet-stream");
            RequestBody payload=Set.of("GET","HEAD").contains(method)?null:RequestBody.create(body==null?new byte[0]:body,MediaType.parse(contentType));
            request.method(method,payload);
            Call call=client.newCall(request.build());
            call.timeout().timeout(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            calls.put(owner,call);
            try(Response response=call.execute()) {
                if(Set.of(301,302,303,307,308).contains(response.code())) {
                    if(hop==5)throw new IOException("重定向次数过多");
                    String location=response.header("Location");
                    HttpUrl next=location==null?null:url.resolve(location);
                    if(next==null)throw new IOException("下载跳转地址无效");
                    next=checked(next.toString(),httpsOnly);
                    if(url.isHttps()&&!next.isHttps())throw new IOException("拒绝 HTTPS 降级跳转");
                    if(!url.host().equals(next.host())||url.port()!=next.port()||!url.scheme().equals(next.scheme()))
                        safeHeaders.entrySet().removeIf(e->Set.of("authorization","cookie","referer").contains(e.getKey().toLowerCase(Locale.ROOT)));
                    if(response.code()==303||((response.code()==301||response.code()==302)&&method.equals("POST"))) { method="GET";body=null; }
                    url=next;
                    continue;
                }
                return reader.read(response);
            } catch(org.springframework.web.server.ResponseStatusException error) { throw error; }
            catch(LxNetworkFailure error) {throw error;}
            catch(Exception error) {
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                if(error instanceof FileSystemException)throw new LxNetworkFailure("无法写入下载文件，请检查挂载、目录权限和剩余空间");
                throw new LxNetworkFailure("连接失败或响应无效，请检查音源和网络");
            } finally { calls.remove(owner,call); }
        }
        throw new LxNetworkFailure("下载跳转次数过多");
    }
    public Reply request(String url,String method,Map<String,String> headers,byte[] body,int limit,boolean httpsOnly,String owner) {
        return perform(url,method,headers,body,httpsOnly,owner,15,response->{
            if(response.body()==null)throw new IOException();
            try(var input=response.body().byteStream()) {
                byte[] bytes=input.readNBytes(limit+1);
                if(bytes.length>limit)throw new IOException("响应超过大小限制");
                var output=new LinkedHashMap<String,String>();
                for(String name:response.headers().names())output.put(name.toLowerCase(Locale.ROOT),response.header(name,""));
                return new Reply(response.code(),output,bytes,response.request().url().toString());
            }
        });
    }
    public Download download(String url,Path temporary,String owner,BooleanSupplier cancelled,Progress progress) {
        return perform(url,"GET",Map.of("Accept-Encoding","identity"),null,false,owner,300,response->{
            if(response.code()!=200||response.body()==null)throw new LxNetworkFailure("音频下载返回 HTTP "+response.code()+"，请重新解析或更换音源");
            long declared=response.body().contentLength();
            if(declared>MAX_AUDIO||declared==0)throw new LxNetworkFailure("音频大小无效或超过 512 MiB");
            long usable=Files.getFileStore(temporary.getParent()).getUsableSpace();
            if(usable<(declared>0?declared:16L*1024*1024)+16L*1024*1024)throw new LxNetworkFailure("下载目录空间不足，请清理空间后重试");
            var digest=MessageDigest.getInstance("SHA-256");
            long received=0,last=0;
            byte[] prefix=new byte[32];int prefixLength=0;
            try(var input=response.body().byteStream();var out=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[65536];int read;
                while((read=input.read(buffer))!=-1) {
                    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    received+=read;
                    if(received>MAX_AUDIO)throw new LxNetworkFailure("音频超过 512 MiB");
                    if(prefixLength<prefix.length) { int n=Math.min(read,prefix.length-prefixLength);System.arraycopy(buffer,0,prefix,prefixLength,n);prefixLength+=n; }
                    digest.update(buffer,0,read);out.write(buffer,0,read);
                    long now=System.nanoTime();
                    if(now-last>1_000_000_000L) { progress.update(received,declared<0?null:declared);last=now; }
                }
            }
            if(received<16||declared>=0&&received!=declared)throw new LxNetworkFailure("音频未完整传输，请重试");
            String extension;
            try {extension=audioExtension(prefix);LxAudioFile.validate(temporary,extension);}catch(IOException e){throw new LxNetworkFailure("返回内容不是完整的音频文件，请重新解析或更换音源");}
            progress.update(received,declared<0?null:declared);
            return new Download(received,HexFormat.of().formatHex(digest.digest()),extension);
        });
    }
    public static String audioExtension(byte[] b) throws IOException {
        if(b.length<12)throw new IOException("音频文件无效");
        String start=new String(b,0,Math.min(12,b.length),java.nio.charset.StandardCharsets.ISO_8859_1);
        if(start.startsWith("fLaC"))return "flac";
        if(start.startsWith("ID3"))return "mp3";
        if(start.startsWith("OggS"))return "ogg";
        if(start.startsWith("RIFF")&&start.substring(8).startsWith("WAVE"))return "wav";
        if(start.substring(4).startsWith("ftyp"))return "m4a";
        if((b[0]&255)==255&&(b[1]&0xf6)==0xf0)return "aac";
        if((b[0]&255)==255&&(b[1]&0xe0)==0xe0&&(b[1]&6)!=0)return "mp3";
        throw new IOException("返回内容不是支持的音频文件");
    }
    public void cancel(String owner) { var call=calls.get(owner);if(call!=null)call.cancel(); }
    public static class LxNetworkFailure extends RuntimeException { public LxNetworkFailure(String message) { super(message); } }
}
