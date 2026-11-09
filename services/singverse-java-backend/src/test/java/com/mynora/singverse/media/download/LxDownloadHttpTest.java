package com.mynora.singverse.media.download;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class LxDownloadHttpTest {
    @TempDir static Path temp;
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicBoolean hold=new AtomicBoolean();
    static final AtomicInteger transfers=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
    static final List<String> audioRequests=new CopyOnWriteArrayList<>();
    static final String BASE="/api/v1/lx-downloads",SOURCES="/api/v1/media-sources";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("singverse.sqlite.path",()->temp.resolve("lx.db").toString());r.add("singverse.auth.key-file",()->temp.resolve("master.key").toString());r.add("singverse.compute.base-url",()->"");r.add("singverse.metadata.cache-path",()->temp.resolve("covers").toString());r.add("singverse.webdav.metadata-path",()->temp.resolve("derived").toString());r.add("singverse.audio.directory",()->temp.resolve("audio").toString());r.add("singverse.transcoding.directory",()->temp.resolve("versions").toString());r.add("singverse.transcoding.cache-directory",()->temp.resolve("cache").toString());}
    @TestConfiguration static class Fixtures {
        @Bean @Primary LxNetwork fixtureNetwork(){return new LxNetwork(new OkHttpClient.Builder().followRedirects(false).addInterceptor(chain->{
            var request=chain.request();String path=request.url().encodedPath(),host=request.url().host();byte[] bytes;
            if(host.equals("audio.public.example")) {
                transfers.incrementAndGet();int concurrent=active.incrementAndGet();peak.accumulateAndGet(concurrent,Math::max);audioRequests.add(request.url().toString());
                try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(hold.get()){if(chain.call().isCanceled()||System.nanoTime()>deadline)throw new java.io.IOException("fixture cancelled");try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}}bytes=LxNetworkTest.wav();}finally{active.decrementAndGet();}
            }else if(path.equals("/source.js"))bytes=script("1").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else if(path.contains("soso"))return LxNetworkTest.response(request,503,"fixture failure".getBytes());
            else if(path.contains("lyric"))bytes="{\"code\":200,\"lrc\":{\"lyric\":\"[00:01.00]测试歌词\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else try{var rows=new ArrayList<JsonNode>();for(int i=101;i<=107;i++){var row=(com.fasterxml.jackson.databind.node.ObjectNode)LxCatalogTest.row("wy");row.put("id",i);rows.add(row);}bytes=JSON.writeValueAsBytes(LxTypes.args("code",200,"result",LxTypes.args("songs",rows,"songCount",rows.size())));}catch(Exception e){throw new java.io.IOException(e);}
            return LxNetworkTest.response(request,200,bytes).newBuilder().header("Content-Type",path.endsWith(".js")?"application/javascript":"application/json").build();
        }).build());}
    }
    static String script(String version){return """
        /**
         * @name 隔离测试音源
         * @version %s
         */
        // fixture-secret-marker
        lx.on(lx.EVENT_NAMES.request, async ({info}) => {
          if(info.type==='flac') throw new Error('该音质不存在');
          return 'https://audio.public.example/'+info.musicInfo.songmid+'.wav?version=%s';
        });
        lx.send(lx.EVENT_NAMES.inited,{sources:Object.fromEntries(['wy','tx','kg','kw','mg'].map(p=>[p,{actions:['musicUrl'],qualitys:['128k','flac']}]))});
        """.formatted(version,version);}
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired LxDownloadService downloads;
    @Autowired MediaScanService scans;
    final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    HttpResponse<String> request(String path,String method,Object data,boolean csrf)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(csrf)b.header("X-CSRF-Token",body(request("/api/v1/auth/csrf","GET",null,false)).path("token").asText());if(data!=null)b.header("Content-Type","application/json");return client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode body(HttpResponse<String> r)throws Exception{return JSON.readTree(r.body());}
    JsonNode ok(String path,String method,Object data)throws Exception{var r=request(path,method,data,!method.equals("GET"));assertThat(r.statusCode()).as(r.body()).isBetween(200,201);return body(r);}
    Map<String,Object> source(Path path,boolean bound){return Map.of("name","测试曲库","type","LOCAL","location",path.toString(),"parserProfileId","default","useForLxDownloads",bound);}
    JsonNode create(List<String> ids,String quality)throws Exception{return ok(BASE+"/batches","POST",Map.of("trackIds",ids,"quality",quality));}
    JsonNode items(String batch)throws Exception{return ok(BASE+"/batches/"+batch+"/items","GET",null).path("items");}
    void complete(String batch)throws Exception{await().atMost(Duration.ofSeconds(35)).untilAsserted(()->assertThat(ok(BASE+"/batches/"+batch,"GET",null).path("status").asText()).isNotIn("QUEUED","RUNNING","CANCELLING"));}
    @Test void authenticatedDownloadsAreDurableDeduplicatedAndCoordinatedWithScanning()throws Exception {
        assertThat(request(BASE+"/status","GET",null,false).statusCode()).isEqualTo(401);
        ok("/api/v1/auth/login","POST",Map.of("username","admin","password","admin"));
        assertThat(request(BASE+"/sources","POST",Map.of("script",script("1")),false).statusCode()).isEqualTo(403);
        assertThat(request(BASE+"/catalog/search","POST",Map.of("query","测试","platform","wy","page",1),true).statusCode()).isEqualTo(409);
        assertThat(request(SOURCES,"POST",source(temp.resolve("missing"),true),true).statusCode()).isBetween(400,409);
        var webdav=new HashMap<>(source(temp,false));webdav.put("type","WEBDAV");webdav.put("location","https://nas.example.invalid/music/");webdav.put("useForLxDownloads",true);assertThat(request(SOURCES,"POST",webdav,true).statusCode()).isEqualTo(400);
        Path root=Files.createDirectory(temp.resolve("downloads")),other=Files.createDirectory(temp.resolve("other"));
        String sourceId=ok(SOURCES,"POST",source(root,true)).path("id").asText();
        assertThat(ok(BASE+"/status","GET",null).path("ready").asBoolean()).isFalse();
        String source1=ok(BASE+"/sources","POST",Map.of("url","https://sources.public.example/source.js?token=fixture-secret-marker","environment","mobile")).path("id").asText();
        ok(BASE+"/sources/"+source1+"/activate","POST",null);
        assertThat(db.queryForObject("SELECT secret_encrypted FROM lx_script WHERE id=?",String.class,source1)).doesNotContain("fixture-secret-marker","https://");
        assertThat(ok(BASE+"/sources","GET",null).toString()).doesNotContain("fixture-secret-marker","script\"","secret_encrypted");
        assertThat(ok(BASE+"/status","GET",null).path("ready").asBoolean()).isTrue();
        var all=ok(BASE+"/catalog/search","POST",Map.of("query","测试","platform","all","page",1));assertThat(all.path("tracks").isEmpty()).isFalse();assertThat(all.path("errors").toString()).contains("tx");
        var tracks=ok(BASE+"/catalog/search","POST",Map.of("query","测试","platform","wy","page",1)).path("tracks");var ids=new ArrayList<String>();tracks.forEach(t->ids.add(t.path("id").asText()));
        assertThat(request(BASE+"/batches","POST",Map.of("trackIds",List.of("forged-client-song"),"quality","auto"),true).statusCode()).isEqualTo(400);
        Files.writeString(root.resolve("测试歌手 - 测试歌曲.wav"),"用户原文件");
        var scanHeld=new CountDownLatch(1);var finishScan=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var scan=executor.submit(()->MediaIndexCoordinator.locked(sourceId,()->{scanHeld.countDown();try{if(!finishScan.await(25,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}return scans.scan(sourceId);}));
            assertThat(scanHeld.await(3,TimeUnit.SECONDS)).isTrue();hold.set(true);
            String batch=create(ids.subList(0,3),"128k").path("batch").path("id").asText();
            var duplicate=create(ids.subList(0,3),"128k");assertThat(duplicate.path("batch").path("id").asText()).isEqualTo(batch);assertThat(duplicate.path("merged").asInt()).isEqualTo(3);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(active.get()).isEqualTo(2));
            assertThat(request(SOURCES+"/"+sourceId,"PUT",source(other,true),true).statusCode()).isEqualTo(409);
            assertThat(request(SOURCES+"/"+sourceId,"PUT",source(root,false),true).statusCode()).isEqualTo(409);
            assertThat(request(SOURCES+"/"+sourceId,"DELETE",null,true).statusCode()).isEqualTo(409);
            String source2=ok(BASE+"/sources","POST",Map.of("script",script("2"))).path("id").asText();ok(BASE+"/sources/"+source2+"/activate","POST",null);
            assertThat(request(BASE+"/sources/"+source1,"DELETE",null,true).statusCode()).isEqualTo(409);
            hold.set(false);finishScan.countDown();scan.get(10,TimeUnit.SECONDS);complete(batch);
            assertThat(ok(BASE+"/batches/"+batch,"GET",null).path("counts").path("SUCCESS").asInt()).as(items(batch).toString()).isEqualTo(3);assertThat(peak.get()).isEqualTo(2);assertThat(audioRequests.subList(0,3)).allSatisfy(url->assertThat(url).contains("version=1"));
            assertThat(Files.readString(root.resolve("测试歌手 - 测试歌曲.wav"))).isEqualTo("用户原文件");assertThat(db.queryForObject("SELECT COUNT(*) FROM lx_download_asset",Integer.class)).isEqualTo(3);
            assertThat(Files.list(root).filter(p->p.toString().endsWith(".lrc")).count()).isEqualTo(3);
            // AUTO and manual requests converge on the same resolved format and transfer only once.
            int before=transfers.get();hold.set(true);String auto=create(List.of(ids.get(3)),"auto").path("batch").path("id").asText(),manual=create(List.of(ids.get(3)),"128k").path("batch").path("id").asText();
            await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(active.get()).isEqualTo(1));hold.set(false);complete(auto);complete(manual);assertThat(transfers.get()-before).isEqualTo(1);
            String failed=create(List.of(ids.get(3)),"flac").path("batch").path("id").asText();complete(failed);assertThat(items(failed).get(0).path("message").asText()).contains("音质不可用");assertThat(transfers.get()-before).isEqualTo(1);
            hold.set(true);String cancelled=create(List.of(ids.get(4)),"128k").path("batch").path("id").asText();await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(active.get()).isEqualTo(1));assertThat(request(BASE+"/batches/"+cancelled+"/cancel","POST",null,true).statusCode()).isEqualTo(204);hold.set(false);complete(cancelled);
            String retried=ok(BASE+"/batches/"+cancelled+"/retry","POST",null).path("batch").path("id").asText();complete(retried);assertThat(items(retried).get(0).path("status").asText()).isEqualTo("SUCCESS");
            // A rescan retains downloaded metadata and later manual corrections.
            String song=items(batch).get(0).path("songId").asText(),review="/api/v1/metadata/songs/"+song+"/review";var context=ok(review,"GET",null);
            ok(review+"/apply","POST",Map.of("requestId",UUID.randomUUID().toString(),"fingerprint",context.path("fingerprint").asText(),"version",context.path("version").asText(),"fields",List.of("title"),"edits",Map.of("title","人工订正")));
            scans.scan(sourceId);var current=ok(review,"GET",null);assertThat(current.at("/values/title").asText()).isEqualTo("人工订正");assertThat(current.at("/song/metadata/origins/title/kind").asText()).isEqualTo("MANUAL");assertThat(current.at("/song/metadata/track/album").asText()).isEqualTo("测试专辑");
            // Startup recovery indexes a fully published file and interrupts an unfinished transfer.
            synchronized(downloads) {
                String recovering=create(ids.subList(5,7),"128k").path("batch").path("id").asText();var pending=items(recovering);String first=pending.get(0).path("id").asText(),second=pending.get(1).path("id").asText();Path published=root.resolve("重启恢复.wav");Files.write(published,LxNetworkTest.wav());
                db.update("UPDATE lx_download_item SET status='PUBLISHING',relative_path='重启恢复.wav',song_id='recovered-song',resolved_quality='128k',content_hash=? WHERE id=?",LxDownloadService.hash(published),first);
                db.update("UPDATE lx_download_item SET status='DOWNLOADING' WHERE id=?",second);Files.writeString(root.resolve(".singverse-downloads").resolve(second+".part"),"incomplete");downloads.recover();
                assertThat(ok(BASE+"/batches/"+recovering,"GET",null).path("status").asText()).isEqualTo("PARTIAL");assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song WHERE id='recovered-song'",Integer.class)).isEqualTo(1);assertThat(Files.exists(root.resolve(".singverse-downloads").resolve(second+".part"))).isFalse();
            }
            var tasks=ok("/api/v1/background-tasks?view=ALL&type=LX_DOWNLOAD","GET",null);assertThat(tasks.toString()).contains("LX_DOWNLOAD","/library/downloads");
            var states=ok(BASE+"/catalog/states","POST",Map.of("trackIds",ids.subList(0,3)));assertThat(states).allSatisfy(t->assertThat(t.path("inLibrary").asBoolean()).isTrue());
            ok(SOURCES+"/"+sourceId,"PUT",source(other,true));assertThat(ok(BASE+"/status","GET",null).path("target").path("path").asText()).isEqualTo(other.toRealPath().toString());
        } finally {hold.set(false);finishScan.countDown();}
    }
}
