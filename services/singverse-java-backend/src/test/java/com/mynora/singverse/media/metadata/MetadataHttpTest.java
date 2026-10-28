package com.mynora.singverse.media.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
class MetadataHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("singverse.sqlite.path", () -> temp.resolve("metadata.db").toString());
        properties.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        properties.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        properties.add("singverse.metadata.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MetadataWorker worker;
    @MockitoSpyBean NeteaseMusicMetadataProvider upstream;
    @MockitoSpyBean QqMusicMetadataProvider qq;
    HttpClient client;
    String sourcePath;
    Path directory;
    static final String BASE = "/api/v1/metadata";

    @BeforeEach void reset() {
        for (String table : List.of("song_metadata", "metadata_candidate", "metadata_item", "metadata_batch", "media_song", "media_source", "filename_parser_config")) db.update("DELETE FROM " + table);
        db.update("UPDATE metadata_settings SET enabled=0,providers_json='[\"NETEASE\"]',auto_apply=0,threshold=0.95 WHERE id=1");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        doReturn(List.of(track("one", "晴天", "周杰伦", "叶惠美"))).when(upstream).search(anyString(), anyInt(), any(Duration.class));
    }
    ExternalTrack track(String id, String title, String artist, String album) {
        return new ExternalTrack(MusicProvider.NETEASE, id, title, List.of(artist), album, 269000,
                "2003-07-31", List.of("Sunny Day"), null, "UNKNOWN", OffsetDateTime.now());
    }
    HttpResponse<String> request(String path, String method, Object data, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf) builder.header("X-CSRF-Token", body(request("/api/v1/auth/csrf", "GET", null, false)).path("token").asText());
        if (data != null) builder.header("Content-Type", "application/json");
        builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    void login() throws Exception {
        assertThat(request("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true).statusCode()).isEqualTo(200);
    }
    void enable(boolean auto) throws Exception {
        assertThat(request(BASE + "/settings", "PUT", Map.of("enabled", true, "providers", List.of("NETEASE"), "autoApply", auto, "threshold", .95), true).statusCode()).isEqualTo(200);
    }
    String song() throws Exception {
        directory = Files.createTempDirectory(temp, "songs-");
        Files.writeString(directory.resolve("周杰伦《晴天》.mkv"), "original");
        var source = body(request("/api/v1/media-sources", "POST", Map.of("name", "歌曲", "type", "LOCAL", "location", directory.toString()), true));
        sourcePath = "/api/v1/media-sources/" + source.path("id").asText();
        assertThat(request(sourcePath + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        return body(request(sourcePath + "/songs", "GET", null, false)).at("/items/0/id").asText();
    }
    JsonNode currentSong() throws Exception { return body(request(sourcePath + "/songs", "GET", null, false)).at("/items/0"); }
    String candidate(String id) throws Exception {
        var response = request(BASE + "/songs/" + id + "/search", "POST", Map.of(), true);
        assertThat(response.statusCode()).isEqualTo(200);
        return body(response).at("/candidates/0/id").asText();
    }
    String start(String songId) throws Exception {
        var response = request(BASE + "/batches", "POST", Map.of("all", false, "songIds", List.of(songId)), true);
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response).path("id").asText();
    }
    JsonNode batch(String id) throws Exception { return body(request(BASE + "/batches/" + id, "GET", null, false)); }

    @Test void defaultsAreDisabledAndAuthenticationAndCsrfAreRequired() throws Exception {
        assertThat(request(BASE + "/settings", "GET", null, false).statusCode()).isEqualTo(401);
        login(); String id = song();
        assertThat(body(request(BASE + "/settings", "GET", null, false)).path("enabled").asBoolean()).isFalse();
        assertThat(body(request(BASE + "/settings", "GET", null, false)).path("autoApply").asBoolean()).isFalse();
        assertThat(request(BASE + "/songs/" + id + "/search", "POST", Map.of(), true).statusCode()).isEqualTo(409);
        verify(upstream, never()).search(anyString(), anyInt(), any(Duration.class));
        assertThat(request(BASE + "/settings", "PUT", Map.of(), false).statusCode()).isEqualTo(403);
        assertThat(request(BASE + "/settings", "PUT", Map.of("enabled", true, "providers", List.of(), "threshold", .95), true).statusCode()).isEqualTo(400);
    }

    @Test void candidatesRequireConfirmationAndSelectedFieldsMergeWithoutChangingFilenameEvidence() throws Exception {
        login(); enable(false); String id = song();
        String original = currentSong().path("result").toString();
        String candidate = candidate(id);
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", candidate, "fields", List.of("title", "artists", "album")), true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("叶惠美");
        doReturn(List.of(track("two", "别的歌名", "别的歌手", "新版专辑"))).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        String next = candidate(id);
        assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", next, "fields", List.of("album")), true).statusCode()).isEqualTo(200);
        var applied = currentSong().path("metadata");
        assertThat(applied.at("/track/title").asText()).isEqualTo("晴天");
        assertThat(applied.at("/track/album").asText()).isEqualTo("新版专辑");
        assertThat(applied.at("/origins/title/externalId").asText()).isEqualTo("one");
        assertThat(applied.at("/origins/album/externalId").asText()).isEqualTo("two");
        assertThat(currentSong().path("result").toString()).isEqualTo(original);
        assertThat(request(sourcePath + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/stale").asBoolean()).isFalse();
        assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("新版专辑");
        Files.writeString(directory.resolve("周杰伦《晴天》.mkv"), "replacement with a different size");
        assertThat(request(sourcePath + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/stale").asBoolean()).isTrue();
        assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", next), true).statusCode()).isEqualTo(409);
        assertThat(Files.readString(directory.resolve("周杰伦《晴天》.mkv"))).isEqualTo("replacement with a different size");
    }

    @Test void batchDefaultIsReviewThenManualApplyAndAppliedSongsAreSkipped() throws Exception {
        login(); enable(false); String id = song(); String batchId = start(id);
        assertThat(request(BASE + "/batches", "POST", Map.of("all", true), true).statusCode()).isEqualTo(409);
        worker.processNext();
        var result = batch(batchId);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("completed").asInt()).isEqualTo(1);
        assertThat(result.path("review").asInt()).isEqualTo(1);
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        String itemId = result.at("/items/0/id").asText(), candidate = result.at("/items/0/candidates/0/id").asText();
        assertThat(request(BASE + "/batches/" + batchId + "/items/" + itemId + "/apply", "POST", Map.of("candidateId", candidate, "fields", List.of("album", "releaseDate")), true).statusCode()).isEqualTo(200);
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("MANUAL_APPLIED");
        assertThat(request(BASE + "/batches", "POST", Map.of("all", true), true).statusCode()).isEqualTo(400);
        assertThat(request(BASE + "/songs/" + id + "/search", "POST", Map.of(), true).statusCode()).isEqualTo(200);
    }

    @Test void explicitAutoApplyWorksButAmbiguousCandidatesWaitForReview() throws Exception {
        login(); enable(true); String id = song(); String batchId = start(id);
        worker.processNext();
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("AUTO_APPLIED");
        assertThat(currentSong().path("metadata").isNull()).isFalse();
        db.update("DELETE FROM song_metadata");
        doReturn(List.of(track("a", "晴天", "周杰伦", "甲专辑"), track("b", "晴天", "周杰伦", "乙专辑"))).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        String ambiguous = start(id); worker.processNext();
        assertThat(batch(ambiguous).at("/items/0/status").asText()).isEqualTo("REVIEW");
        assertThat(batch(ambiguous).at("/items/0/confidence").asText()).isEqualTo("HIGH");
        assertThat(currentSong().path("metadata").isNull()).isTrue();
    }

    @Test void pausingInflightWorkAndDisablingAutoApplyPreventsWritesOnResume() throws Exception {
        login(); enable(true); String id = song(); String batchId = start(id);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); return List.of(track("one", "晴天", "周杰伦", "叶惠美")); }).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> running = executor.submit(worker::processNext);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(request(BASE + "/batches/" + batchId + "/pause", "POST", null, true).statusCode()).isEqualTo(200);
            release.countDown(); running.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(batch(batchId).path("status").asText()).isEqualTo("PAUSED");
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("PENDING");
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        enable(false);
        assertThat(request(BASE + "/batches/" + batchId + "/resume", "POST", null, true).statusCode()).isEqualTo(200);
        worker.processNext();
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("REVIEW");
    }

    @Test void upstreamFailureCanRetryAndRestartRecoversDurableWorkPaused() throws Exception {
        login(); enable(false); String id = song(); String batchId = start(id);
        doThrow(new MusicSourceException(MusicProvider.NETEASE, "test upstream unavailable")).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        worker.processNext();
        var failed = batch(batchId);
        assertThat(failed.at("/items/0/status").asText()).isEqualTo("FAILED");
        String item = failed.at("/items/0/id").asText();
        assertThat(request(BASE + "/batches/" + batchId + "/items/" + item + "/retry", "POST", null, true).statusCode()).isEqualTo(200);
        db.update("UPDATE metadata_item SET status='PROCESSING' WHERE id=?", item);
        worker.recover();
        assertThat(batch(batchId).path("status").asText()).isEqualTo("PAUSED");
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("PENDING");
        assertThat(request(BASE + "/batches/" + batchId + "/cancel", "POST", null, true).statusCode()).isEqualTo(200);
        doReturn(List.of(track("one", "晴天", "周杰伦", "叶惠美"))).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        String next = start(id); worker.processNext();
        assertThat(batch(next).at("/items/0/status").asText()).isEqualTo("REVIEW");
    }

    @Test void invalidCandidateOwnershipFieldsAndCoverUrlsAreRejected() throws Exception {
        login(); enable(false); String id = song(), candidate = candidate(id);
        assertThat(request(BASE + "/songs/missing/apply", "POST", Map.of("candidateId", candidate), true).statusCode()).isEqualTo(400);
        assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", candidate, "fields", List.of("password")), true).statusCode()).isEqualTo(400);
        assertThatThrownBy(() -> MetadataCoverService.checked(MusicProvider.NETEASE, "https://127.0.0.1/cover"));
        assertThatThrownBy(() -> MetadataCoverService.checked(MusicProvider.QQ, "https://y.gtimg.cn.attacker.invalid/cover"));
        assertThatThrownBy(() -> MetadataCoverService.validate("<svg onload=\"alert(1)\" />".getBytes()));
    }

    @Test void failedApplyRollsBackOldOverlayAndWorkerRecordsFailure() throws Exception {
        login(); enable(true); String id = song(), candidate = candidate(id);
        assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", candidate), true).statusCode()).isEqualTo(200);
        String before = currentSong().path("metadata").toString();
        db.execute("CREATE TRIGGER fail_metadata_insert BEFORE INSERT ON song_metadata BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        try {
            assertThat(request(BASE + "/songs/" + id + "/apply", "POST", Map.of("candidateId", candidate), true).statusCode()).isEqualTo(500);
            assertThat(currentSong().path("metadata").toString()).isEqualTo(before);
            db.update("UPDATE song_metadata SET fingerprint='changed' WHERE song_id=?", id);
            String batchId = start(id); worker.processNext();
            assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("FAILED");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM song_metadata WHERE song_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("叶惠美");
        } finally { db.execute("DROP TRIGGER fail_metadata_insert"); }
    }

    @Test void confirmationDuringUpstreamQueryIsNeverOverwrittenByAutomaticBatch() throws Exception {
        login(); enable(true); String id = song(), candidate = candidate(id), batchId = start(id);
        doAnswer(invocation -> {
            assertThat(request(BASE + "/songs/" + id + "/apply", "POST",
                    Map.of("candidateId", candidate, "fields", List.of("album")), true).statusCode()).isEqualTo(200);
            return List.of(track("new", "晴天", "周杰伦", "另一个专辑"));
        }).when(upstream).search(anyString(), anyInt(), any(Duration.class));
        worker.processNext();
        assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("MANUAL_APPLIED");
        assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("叶惠美");
        assertThat(currentSong().at("/metadata/fields").toString()).isEqualTo("[\"album\"]");
    }

    @Test void rateSettingsPersistAndRejectInvalidRanges() throws Exception {
        login();
        var defaults = body(request(BASE + "/settings", "GET", null, false));
        assertThat(defaults.path("minIntervalSeconds").asInt()).isEqualTo(3);
        assertThat(defaults.path("maxIntervalSeconds").asInt()).isEqualTo(8);
        var input = new java.util.HashMap<String,Object>(Map.of("enabled", true, "providers", List.of("NETEASE"), "autoApply", false, "threshold", .95, "minIntervalSeconds", 10, "maxIntervalSeconds", 20));
        assertThat(request(BASE + "/settings", "PUT", input, true).statusCode()).isEqualTo(200);
        assertThat(body(request(BASE + "/settings", "GET", null, false)).path("maxIntervalSeconds").asInt()).isEqualTo(20);
        input.put("minIntervalSeconds", 21);
        assertThat(request(BASE + "/settings", "PUT", input, true).statusCode()).isEqualTo(400);
        input.put("minIntervalSeconds", 1);
        assertThat(request(BASE + "/settings", "PUT", input, true).statusCode()).isEqualTo(400);
        input.put("minIntervalSeconds", 3); input.put("maxIntervalSeconds", 301);
        assertThat(request(BASE + "/settings", "PUT", input, true).statusCode()).isEqualTo(400);
    }

    Map<String,Object> reviewInput(String id, List<String> fields, Map<String,Object> edits) throws Exception {
        var context=body(request(BASE+"/songs/"+id+"/review","GET",null,false));
        return new LinkedHashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"fingerprint",context.path("fingerprint").asText(),"version",context.path("version").asText(),"fields",fields,"edits",edits));
    }
    @Test void manualReviewWorksWithoutPlatformAndPersistsOriginsAndIdempotentReceipt() throws Exception {
        login(); String id=song();
        var input=reviewInput(id,List.of("title","album","artists","releaseDate","durationMs"),Map.of("title","手工歌名","album","自选专辑","artists",List.of("手工歌手"),"releaseDate","2020-02-29","durationMs",238000));
        String path=BASE+"/songs/"+id+"/review/apply";
        var first=request(path,"POST",input,true);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(body(first).path("updated").size()).isEqualTo(5);
        assertThat(currentSong().at("/result/title").asText()).isEqualTo("晴天");
        assertThat(currentSong().at("/metadata/origins/title/kind").asText()).isEqualTo("MANUAL");
        assertThat(body(request(BASE+"/songs/"+id+"/review","GET",null,false)).at("/values/title").asText()).isEqualTo("手工歌名");
        assertThat(request(path,"POST",input,true).body()).isEqualTo(first.body());
        input.put("requestId",UUID.randomUUID().toString());
        assertThat(request(path,"POST",input,true).statusCode()).isEqualTo(409);
        var next=reviewInput(id,List.of("album"),Map.of("album","另一专辑","title","不应写入"));
        assertThat(request(path,"POST",next,true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/track/title").asText()).isEqualTo("手工歌名");
        var empty=reviewInput(id,List.of("title"),Map.of("title",""));
        assertThat(body(request(path,"POST",empty,true)).path("updated").size()).isZero();
        assertThat(currentSong().at("/metadata/track/title").asText()).isEqualTo("手工歌名");
    }
    @Test void manualValidationAndFileChangesRejectWithoutPartialWrites() throws Exception {
        login(); String id=song(); String path=BASE+"/songs/"+id+"/review/apply";
        for(var edits:List.of(Map.<String,Object>of("releaseDate","2023-02-29"),Map.<String,Object>of("durationMs",-1),Map.<String,Object>of("artists","not-array"),Map.<String,Object>of("title","x".repeat(501)))) {
            var input=reviewInput(id,new ArrayList<>(edits.keySet()),edits);
            assertThat(request(path,"POST",input,true).statusCode()).isEqualTo(400);
        }
        var input=reviewInput(id,List.of("album"),Map.of("album","新专辑"));
        db.update("UPDATE media_song SET file_size=file_size+1 WHERE id=?",id);
        assertThat(request(path,"POST",input,true).statusCode()).isEqualTo(409);
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        assertThat(request(path,"POST",input,false).statusCode()).isEqualTo(403);
    }
    HttpResponse<byte[]> uploadCover(String id, byte[] bytes, String mime, boolean csrf) throws Exception {
        String fingerprint=body(request(BASE+"/songs/"+id+"/review","GET",null,false)).path("fingerprint").asText();
        String boundary="singverse-boundary";
        var output=new java.io.ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"fingerprint\"\r\n\r\n"+fingerprint+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../unsafe.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(bytes); output.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+BASE+"/songs/"+id+"/covers/upload")).header("Content-Type","multipart/form-data; boundary="+boundary);
        if(csrf) builder.header("X-CSRF-Token",body(request("/api/v1/auth/csrf","GET",null,false)).path("token").asText());
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void uploadedCoverIsManagedPreviewedAndOnlyAppliedAfterConfirmation() throws Exception {
        login(); String id=song(); var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        assertThat(uploadCover(id,"not-image".getBytes(),"image/png",true).statusCode()).isEqualTo(400);
        assertThat(uploadCover(id,bytes.toByteArray(),"image/jpeg",true).statusCode()).isEqualTo(400);
        assertThat(uploadCover(id,bytes.toByteArray(),"image/png",false).statusCode()).isEqualTo(403);
        var uploaded=uploadCover(id,bytes.toByteArray(),"image/png",true);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        var asset=json.readTree(uploaded.body());
        assertThat(asset.path("width").asInt()).isEqualTo(4);
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        assertThat(request(BASE+"/cover-assets/"+asset.path("id").asText(),"GET",null,false).statusCode()).isEqualTo(200);
        var input=reviewInput(id,List.of("cover"),Map.of()); input.put("coverAssetId",asset.path("id").asText());
        assertThat(request(BASE+"/songs/"+id+"/review/apply","POST",input,true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/origins/cover/kind").asText()).isEqualTo("UPLOAD");
        assertThat(request(BASE+"/songs/"+id+"/cover","GET",null,false).statusCode()).isEqualTo(200);
        var anonymous=HttpClient.newHttpClient();
        assertThat(anonymous.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+BASE+"/cover-assets/"+asset.path("id").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(401);
    }
    @Test void coverSearchReusesRealProviderAdapterAndCandidateOwnership() throws Exception {
        login(); enable(false); String id=song();
        var track=new ExternalTrack(MusicProvider.NETEASE,"cover","晴天",List.of("周杰伦"),"专辑",238000,"2003",List.of(),"https://p1.music.126.net/cover.png","UNKNOWN",null);
        doReturn(List.of(track)).when(upstream).search(anyString(),anyInt(),any(Duration.class));
        var response=request(BASE+"/songs/"+id+"/covers/search","POST",Map.of("keyword","晴天 周杰伦 专辑","providers",List.of("NETEASE")),true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).at("/items/0/provider").asText()).isEqualTo("NETEASE");
        var apply=reviewInput(id,List.of("cover"),Map.of()); apply.put("coverCandidateId",body(response).at("/items/0/candidateId").asText());
        assertThat(request(BASE+"/songs/"+id+"/review/apply","POST",apply,true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/origins/cover/provider").asText()).isEqualTo("NETEASE");
    }

    @Test void singleReviewTransactionFailureRollsBackOverlayAndReceipt() throws Exception {
        login(); String id=song(); String path=BASE+"/songs/"+id+"/review/apply";
        var initial=reviewInput(id,List.of("album"),Map.of("album","原专辑"));
        assertThat(request(path,"POST",initial,true).statusCode()).isEqualTo(200);
        var input=reviewInput(id,List.of("album"),Map.of("album","替换专辑"));
        db.execute("CREATE TRIGGER fail_review_receipt BEFORE INSERT ON metadata_review_receipt BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        try {
            assertThat(request(path,"POST",input,true).statusCode()).isEqualTo(500);
            assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("原专辑");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM metadata_review_receipt WHERE request_id=?",Integer.class,input.get("requestId"))).isZero();
        } finally { db.execute("DROP TRIGGER fail_review_receipt"); }
        assertThat(request(path,"POST",input,true).statusCode()).isEqualTo(200);
        assertThat(currentSong().at("/metadata/track/album").asText()).isEqualTo("替换专辑");
    }
    @Test void concurrentSingleReviewsCommitOneAndRejectStaleVersion() throws Exception {
        login(); String id=song(); String path=BASE+"/songs/"+id+"/review/apply";
        var a=reviewInput(id,List.of("album"),Map.of("album","甲"));
        var b=reviewInput(id,List.of("album"),Map.of("album","乙"));
        String csrf=body(request("/api/v1/auth/csrf","GET",null,false)).path("token").asText();
        var one=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(a))).build();
        var two=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(b))).build();
        var first=client.sendAsync(one,HttpResponse.BodyHandlers.ofString());
        var second=client.sendAsync(two,HttpResponse.BodyHandlers.ofString());
        assertThat(List.of(first.get(10,TimeUnit.SECONDS).statusCode(),second.get(10,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
    }

    void assertBatchCounts(JsonNode value) {
        int pending=value.path("pending").asInt(),processing=value.path("processing").asInt();
        int done=value.path("review").asInt()+value.path("applied").asInt()+value.path("failed").asInt()+value.path("skippedExisting").asInt();
        assertThat(value.path("total").asInt()).isEqualTo(pending+processing+done);
        assertThat(value.path("completed").asInt()).isEqualTo(done);
    }
    @Test void skippedSongsAreDurableItemsAndCountAsCompletedAcrossLifecycle() throws Exception {
        login(); enable(false); String id=song(),candidate=candidate(id);
        assertThat(request(BASE+"/songs/"+id+"/apply","POST",Map.of("candidateId",candidate),true).statusCode()).isEqualTo(200);
        Files.writeString(directory.resolve("周杰伦《稻香》.mkv"),"placeholder");
        Files.writeString(directory.resolve("周杰伦《夜曲》.mkv"),"placeholder");
        assertThat(request(sourcePath+"/scan","POST",null,true).statusCode()).isEqualTo(200);
        var created=body(request(BASE+"/batches","POST",Map.of("all",true),true)); String batchId=created.path("id").asText();
        assertBatchCounts(created);
        assertThat(created.path("total").asInt()).isEqualTo(3);
        assertThat(created.path("completed").asInt()).isEqualTo(1);
        assertThat(created.path("processableTotal").asInt()).isEqualTo(2);
        assertThat(created.path("skippedExisting").asInt()).isEqualTo(1);
        var skipped=body(request(BASE+"/batches/"+batchId+"?status=SKIPPED_EXISTING","GET",null,false));
        assertThat(skipped.path("totalElements").asInt()).isEqualTo(1);
        assertThat(skipped.at("/items/0/songId").asText()).isEqualTo(id);
        assertThat(skipped.at("/items/0/error").asText()).contains("文件未变化");
        worker.processNext(); var processed=batch(batchId); assertBatchCounts(processed);
        assertThat(processed.path("completed").asInt()).isEqualTo(2);
        assertThat(processed.path("review").asInt()).isEqualTo(1);
        assertThat(processed.path("pending").asInt()).isEqualTo(1);
        assertBatchCounts(body(request(BASE+"/batches/"+batchId+"/pause","POST",null,true)));
        assertBatchCounts(body(request(BASE+"/batches/"+batchId+"/resume","POST",null,true)));
        worker.processNext(); var complete=batch(batchId); assertBatchCounts(complete);
        assertThat(complete.path("completed").asInt()).isEqualTo(3);
        assertThat(complete.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(complete.path("applied").asInt()).isZero();
        assertBatchCounts(body(request(BASE+"/batches/latest","GET",null,false)));
        // Historic batches did not persist skipped IDs; keep honest aggregate coverage.
        db.update("DELETE FROM metadata_item WHERE batch_id=? AND status='SKIPPED_EXISTING'",batchId);
        var legacy=batch(batchId); assertBatchCounts(legacy);
        assertThat(legacy.path("legacySkipped").asInt()).isEqualTo(1);
        assertThat(legacy.path("totalElements").asInt()).isEqualTo(2);
        assertThat(legacy.path("total").asInt()).isEqualTo(3);
    }
    @Test void batchFilteringSortingConfidenceAndTerminationKeepRealStates() throws Exception {
        login(); enable(false); String id=song();
        doReturn(List.of(track("unrelated","完全不相关","陌生歌手","陌生专辑"))).when(upstream).search(anyString(),anyInt(),any(Duration.class));
        String batchId=start(id); worker.processNext();
        var completed=batch(batchId); assertBatchCounts(completed);
        assertThat(completed.at("/items/0/confidence").asText()).isEqualTo("NONE");
        assertThat(completed.at("/items/0/status").asText()).isEqualTo("REVIEW");
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        String itemId=completed.at("/items/0/id").asText();
        var filtered=body(request(BASE+"/batches/"+batchId+"?q="+URLEncoder.encode("周杰伦",java.nio.charset.StandardCharsets.UTF_8)+"&sort=SCORE_DESC&status=REVIEW","GET",null,false));
        assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
        assertBatchCounts(filtered);
        assertThat(body(request(BASE+"/batches/"+batchId+"?q=%25","GET",null,false)).path("totalElements").asInt()).isZero();
        assertThat(request(BASE+"/batches/"+batchId+"?sort=invalid","GET",null,false).statusCode()).isEqualTo(400);
        var retry=body(request(BASE+"/batches/"+batchId+"/items/"+itemId+"/retry","POST",null,true));
        assertBatchCounts(retry); assertThat(retry.path("pending").asInt()).isEqualTo(1);
        assertThat(currentSong().path("metadata").isNull()).isTrue();
        var cancelled=body(request(BASE+"/batches/"+batchId+"/cancel","POST",null,true));
        assertBatchCounts(cancelled); assertThat(cancelled.path("pending").asInt()).isEqualTo(1);
        worker.processNext(); assertThat(batch(batchId).at("/items/0/status").asText()).isEqualTo("PENDING");
        assertThat(request(BASE+"/batches/"+batchId+"/items/"+itemId+"/retry","POST",null,true).statusCode()).isEqualTo(409);
    }

    @Test void batchPartialProviderFailureIsStoredWithoutLosingSuccessfulCandidates() throws Exception {
        login();
        assertThat(request(BASE+"/settings","PUT",Map.of("enabled",true,"providers",List.of("NETEASE","QQ"),"autoApply",false,"threshold",.95),true).statusCode()).isEqualTo(200);
        doThrow(new MusicSourceException(MusicProvider.QQ,"平台返回 HTTP 429")).when(qq).search(anyString(),anyInt(),any(Duration.class));
        String id=song(),batchId=start(id); worker.processNext();
        var value=batch(batchId); assertBatchCounts(value);
        assertThat(value.at("/items/0/status").asText()).isEqualTo("REVIEW");
        assertThat(value.at("/items/0/candidates").size()).isGreaterThan(0);
        assertThat(value.at("/items/0/error").asText()).contains("部分平台失败","QQ","429");
        assertThat(currentSong().path("metadata").isNull()).isTrue();
    }

}
