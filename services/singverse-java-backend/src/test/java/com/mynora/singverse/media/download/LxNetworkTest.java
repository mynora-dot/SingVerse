package com.mynora.singverse.media.download;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LxNetworkTest {
    @TempDir Path temp;
    static byte[] wav(){byte[] b=new byte[444];var v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);v.put("RIFF".getBytes()).putInt(b.length-8).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(400);return b;}
    static Response response(Request r,int status,byte[] bytes){return new Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(ResponseBody.create(bytes,MediaType.get("application/octet-stream"))).build();}
    static LxNetwork network(Interceptor interceptor){return new LxNetwork(new OkHttpClient.Builder().followRedirects(false).addInterceptor(interceptor).build());}
    @Test void rejectsPrivateAndReservedAddressesIncludingAlternateIpRepresentations()throws Exception {
        for(String address:List.of("127.0.0.1","10.1.2.3","169.254.169.254","172.20.0.1","192.168.2.1","100.64.0.1","198.18.0.1","0.0.0.0","::1","fc00::1","fe80::1","::ffff:127.0.0.1","2001:db8::1","2002:0a00:0001::1"))assertThat(LxNetwork.publicAddress(InetAddress.getByName(address))).as(address).isFalse();
        for(String address:List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111"))assertThat(LxNetwork.publicAddress(InetAddress.getByName(address))).as(address).isTrue();
        for(String address:List.of("http://2130706433/x","http://127.1/x","file:///etc/passwd","https://name:password@example.com","https://localhost/test","https://example.com/x#fragment"))assertThatThrownBy(()->LxNetwork.checked(address,false)).as(address).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->LxNetwork.checked("http://example.com/source.js",true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void checksEveryRedirectAndStripsCrossOriginCredentials() {
        var requests=new ArrayList<Request>();
        var network=network(chain->{var request=chain.request();requests.add(request);return requests.size()==1?response(request,302,new byte[0]).newBuilder().header("Location","https://other.example/final").build():response(request,200,"ok".getBytes());});
        assertThat(network.request("https://public.example/start","GET",Map.of("Authorization","Bearer fixture","Cookie","fixture=1"),null,100,false,"test").status()).isEqualTo(200);
        assertThat(requests.get(1).header("Authorization")).isNull();assertThat(requests.get(1).header("Cookie")).isNull();
        var blocked=network(chain->response(chain.request(),302,new byte[0]).newBuilder().header("Location","http://127.0.0.1/private").build());
        assertThatThrownBy(()->blocked.request("https://public.example/start","GET",Map.of(),null,100,false,"test")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var downgrade=network(chain->response(chain.request(),302,new byte[0]).newBuilder().header("Location","http://public.example/plain").build());
        assertThatThrownBy(()->downgrade.request("https://public.example/start","GET",Map.of(),null,100,false,"test")).isInstanceOf(LxNetwork.LxNetworkFailure.class);
        var loop=network(chain->response(chain.request(),302,new byte[0]).newBuilder().header("Location","/again").build());
        assertThatThrownBy(()->loop.request("https://public.example/start","GET",Map.of(),null,100,false,"test")).isInstanceOf(LxNetwork.LxNetworkFailure.class);
    }
    @Test void publishesOnlyCompleteAudioAndDoesNotOverwriteFiles()throws Exception {
        byte[] audio=wav();var network=network(chain->response(chain.request(),200,audio));Path file=temp.resolve("one.part");
        var progress=new AtomicInteger();var result=network.download("https://public.example/audio",file,"one",()->false,(n,total)->{assertThat(total).isEqualTo(audio.length);progress.incrementAndGet();});
        assertThat(result.extension()).isEqualTo("wav");assertThat(result.hash()).isEqualTo(LxDownloadService.hash(file));assertThat(result.size()).isEqualTo(audio.length);assertThat(progress.get()).isPositive();
        assertThatThrownBy(()->network.download("https://public.example/audio",file,"two",()->false,(n,t)->{})).isInstanceOf(LxNetwork.LxNetworkFailure.class);assertThat(Files.readAllBytes(file)).isEqualTo(audio);
        var html=network(chain->response(chain.request(),200,"<html>upstream error page</html>".getBytes()));
        assertThatThrownBy(()->html.download("https://public.example/audio",temp.resolve("html.part"),"html",()->false,(n,t)->{})).hasMessageContaining("不是完整的音频");
        byte[] truncated=Arrays.copyOf(audio,audio.length-1);var cut=network(chain->response(chain.request(),200,truncated));
        assertThatThrownBy(()->cut.download("https://public.example/audio",temp.resolve("cut.part"),"cut",()->false,(n,t)->{})).hasMessageContaining("不是完整的音频");
        assertThatThrownBy(()->network.download("https://public.example/audio",temp.resolve("cancel.part"),"cancel",()->true,(n,t)->{})).isInstanceOf(LxNetwork.LxNetworkFailure.class);
        assertThatThrownBy(()->network.download("https://public.example/audio",temp.resolve("missing/file.part"),"disk",()->false,(n,t)->{})).hasMessageContaining("挂载");
    }
    @Test void catchesDeclaredLengthMismatchAndBoundsBridgeResponses() {
        byte[] bytes=wav();var network=network(chain->response(chain.request(),200,bytes).newBuilder().body(new ResponseBody(){public MediaType contentType(){return MediaType.get("audio/wav");}public long contentLength(){return bytes.length+3;}public BufferedSource source(){return new Buffer().write(bytes);}}).build());
        assertThatThrownBy(()->network.download("https://public.example/audio",temp.resolve("length.part"),"length",()->false,(n,t)->{})).hasMessageContaining("未完整传输");
        assertThatThrownBy(()->network.request("https://public.example/large","GET",Map.of(),null,100,false,"large")).isInstanceOf(LxNetwork.LxNetworkFailure.class);
    }
    @Test void checksMp3AacAndMp4FrameBoundaries()throws Exception {
        byte[] mp3=new byte[417];mp3[0]=(byte)255;mp3[1]=(byte)251;mp3[2]=(byte)144;Path file=temp.resolve("frames");Files.write(file,mp3);LxAudioFile.validate(file,"mp3");Files.write(file,Arrays.copyOf(mp3,416));assertThatThrownBy(()->LxAudioFile.validate(file,"mp3")).isInstanceOf(java.io.IOException.class);
        byte[] aac={(byte)255,(byte)241,80,(byte)128,1,63,(byte)252,0,0};Files.write(file,aac);LxAudioFile.validate(file,"aac");Files.write(file,Arrays.copyOf(aac,8));assertThatThrownBy(()->LxAudioFile.validate(file,"aac")).isInstanceOf(java.io.IOException.class);
        var mp4=ByteBuffer.allocate(40).putInt(16).put("ftypM4A ".getBytes()).putInt(0).putInt(12).put("moov".getBytes()).putInt(0).putInt(12).put("mdat".getBytes()).putInt(1).array();Files.write(file,mp4);LxAudioFile.validate(file,"m4a");Files.write(file,Arrays.copyOf(mp4,39));assertThatThrownBy(()->LxAudioFile.validate(file,"m4a")).isInstanceOf(java.io.IOException.class);
    }
    @Test void concurrentFirstDownloadsShareOnlyARealPrivateDirectory()throws Exception {
        Path root=temp.toRealPath();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var gate=new java.util.concurrent.CountDownLatch(1);var jobs=new ArrayList<java.util.concurrent.Future<Path>>();
            for(int i=0;i<24;i++){String id="item-"+i;jobs.add(executor.submit(()->{gate.await();return LxDownloadService.temporary(root,id);}));}
            gate.countDown();for(var job:jobs)assertThat(job.get().getParent()).isEqualTo(root.resolve(".singverse-downloads"));
        }
        Files.delete(root.resolve(".singverse-downloads"));Files.createSymbolicLink(root.resolve(".singverse-downloads"),root);
        assertThatThrownBy(()->LxDownloadService.temporary(root,"escape")).hasMessageContaining("临时目录不可写");
    }
}
