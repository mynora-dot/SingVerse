package com.mynora.singverse.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.model.PlaybackFingerprint;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.mynora.singverse.media.transcoding.SongCacheService;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import com.mynora.singverse.media.transcoding.TranscodeService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real scan publication, XML projections, original bytes and authorized HLS/derived endpoints. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "singverse.metadata.worker-enabled=false", "singverse.webdav.request-interval-ms=0",
        "singverse.webdav.metadata-worker-enabled=false", "singverse.webdav.share-worker-enabled=false",
        "singverse.classification.worker-enabled=false"})
@ActiveProfiles("sqlite")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlaybackFingerprintHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("fingerprint.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.transcoding.cache-minimum-free-bytes", () -> 0);
        p.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        p.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        p.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MediaSongRepository songs;
    @Autowired MediaSourceRepository sources;
    @Autowired SongCacheService cache;
    @Autowired TranscodeService transcode;
    @Autowired MediaPlaybackService playback;
    @MockitoBean TranscodeClient compute;
    HttpServer dav;
    ExecutorService workers;
    HttpClient client;
    String sourcePath, songId, rootPath;
    final List<String> sessions = new ArrayList<>();
    final AtomicInteger mediaGets = new AtomicInteger();
    volatile byte[] media;
    volatile String modified, etag;
    volatile boolean sizeKnown;
    volatile Runnable beforeMedia = () -> {};

    @BeforeEach void start() throws Exception {
        media = new byte[64 * 1024 + 37]; Arrays.fill(media, (byte) 17);
        modified = "Sat, 07 Nov 2026 00:00:00 GMT"; etag = "\"v1\""; sizeKnown = true; rootPath = "/dav/";
        dav = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); dav.setExecutor(workers);
        dav.createContext("/", exchange -> { try { upstream(exchange); } finally { exchange.close(); } });
        dav.start();
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        assertThat(request("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin")).statusCode()).isEqualTo(200);
        var connection = new Connection(URI.create("http://127.0.0.1:9123"), "fixture-compute-token", List.of());
        when(compute.connection()).thenReturn(connection); when(compute.connection(anyString())).thenReturn(connection);
        when(compute.call(eq(connection), anyString(), anyString(), any())).thenAnswer(call -> {
            String path = call.getArgument(1);
            if (path.endsWith("/negotiate")) return json.createObjectNode().put("mode", "TRANSCODE")
                    .set("media", json.createObjectNode().put("duration", 90).set("streams", json.createArrayNode()));
            return json.createObjectNode().put("state", "RUNNING").put("ready", true).put("offset", 0);
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(() ->
                assertThat(cache.list(0, 20).path("usageReady").asBoolean()).isTrue());
    }

    @AfterEach void close() {
        beforeMedia = () -> {};
        for (String id : sessions) transcode.cancel(id, "admin");
        if (dav != null) dav.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    private String location() { return "http://127.0.0.1:" + dav.getAddress().getPort() + rootPath; }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> request(String path, String method, Object data) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (!method.equals("GET")) {
            var csrf = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            request.header("X-CSRF-Token", json.readTree(csrf.body()).path("token").asText());
        }
        if (data != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String path, String method, Object data) throws Exception {
        var response = request(path, method, data);
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
    }
    private void source() throws Exception {
        var created = ok("/api/v1/media-sources", "POST", sourceInput("reader", "fixture-password"));
        assertThat(created.has("configurationFingerprint")).isFalse();
        sourcePath = "/api/v1/media-sources/" + created.path("id").asText();
        scan();
    }
    private Map<String, Object> sourceInput(String username, String password) {
        return Map.of("name", "fingerprint-fixture", "type", "WEBDAV", "location", location(),
                "username", username, "password", password, "parserProfileId", "default", "scanEnabled", false);
    }
    private void scan() throws Exception {
        String task = ok(sourcePath + "/scan-tasks", "POST", null).path("id").asText();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            var state = ok(sourcePath + "/scan-tasks/" + task, "GET", null);
            if (state.path("status").asText().equals("SUCCEEDED")) break;
            assertThat(state.path("status").asText()).isIn("QUEUED", "RUNNING");
            if (System.nanoTime() > deadline) throw new AssertionError("Scan did not complete");
            Thread.sleep(10);
        }
        var page = ok(sourcePath + "/songs", "GET", null);
        assertThat(page.path("items")).hasSize(1); songId = page.path("items").get(0).path("id").asText();
        assertThat(page.path("items").get(0).has("playbackFingerprint")).isFalse();
    }
    private HttpResponse<byte[]> original() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/v1/media-songs/" + songId + "/video"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private String hls() throws Exception {
        var session = ok("/api/v1/transcoding/playback/" + songId, "POST",
                Map.of("force", true, "client", Map.of("hls", true, "video", List.of("h264"))));
        assertThat(session.path("status").asText()).isEqualTo("READY");
        String id = session.path("id").asText(); sessions.add(id); return id;
    }
    private String offline() throws Exception {
        String id = ok("/api/v1/transcoding/offline", "POST", Map.of("songIds", List.of(songId))).get(0).asText();
        sessions.add(id);
        Files.createDirectories(temp.resolve("versions")); Files.write(temp.resolve("versions").resolve(id + ".mp4"), new byte[] {1, 2, 3});
        db.update("UPDATE media_transcode SET state='SUCCEEDED' WHERE id=?", id);
        return id;
    }
    private long entries() { return db.queryForObject("SELECT COUNT(*) FROM song_cache_entry WHERE song_id=?", Long.class, songId); }

    @Test void unchangedScanReusesOriginalAndKeepsHlsAndOfflineAuthorization() throws Exception {
        source(); var song = songs.get(songId); var source = sources.get(song.sourceId());
        assertThat(original().body()).isEqualTo(media); assertThat(entries()).isEqualTo(1);
        String id = hls(), offline = offline(); int fetched = mediaGets.get();
        scan();
        var current = songs.get(songId); var currentSource = sources.get(current.sourceId());
        assertThat(currentSource.updatedAt()).isNotEqualTo(source.updatedAt());
        assertThat(current.scannedAt()).isNotEqualTo(song.scannedAt());
        assertThat(PlaybackFingerprint.of(current, currentSource)).isEqualTo(PlaybackFingerprint.of(song, source));
        assertThat(original().body()).isEqualTo(media); assertThat(mediaGets.get()).isEqualTo(fetched); assertThat(entries()).isEqualTo(1);
        assertThat(ok("/api/v1/transcoding/sessions/" + id, "GET", null).path("status").asText()).isEqualTo("READY");
        transcode.tick();
        assertThat(ok("/api/v1/transcoding/sessions/" + id, "GET", null).path("state").asText()).isEqualTo("RUNNING");
        assertThat(request("/api/v1/transcoding/sessions/versions/" + offline + "/" + songId, "GET", null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest @ValueSource(strings = {"size", "mtime", "etag", "unknown-version", "unknown-size"})
    void changedOrIncompleteObservationInvalidatesPlaybackWithoutChangingFrozenMetadata(String change) throws Exception {
        if (change.equals("unknown-version")) { modified = null; etag = null; }
        if (change.equals("unknown-size")) sizeKnown = false;
        source(); String metadata = MediaSongRepository.fingerprint(songs.get(songId));
        assertThat(original().body()).isEqualTo(media); String id = hls(), version = offline(); int fetched = mediaGets.get();
        switch (change) {
            case "size" -> media = Arrays.copyOf(media, media.length + 1);
            case "mtime" -> { modified = "Sat, 07 Nov 2026 01:00:00 GMT"; media[0] = 31; }
            case "etag" -> { etag = "\"v2\""; media[0] = 41; }
            default -> { }
        }
        scan();
        assertThat(MediaSongRepository.fingerprint(songs.get(songId))).isEqualTo(metadata);
        assertThat(original().body()).isEqualTo(media); assertThat(mediaGets.get()).isEqualTo(fetched + 1); assertThat(entries()).isEqualTo(2);
        assertThat(request("/api/v1/transcoding/sessions/" + id, "GET", null).statusCode()).isEqualTo(409);
        assertThat(request("/api/v1/transcoding/sessions/versions/" + version + "/" + songId, "GET", null).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest @ValueSource(strings = {"password", "username", "address"})
    void realSourceConfigurationChangesIsolateOriginalAndHls(String change) throws Exception {
        source(); assertThat(original().body()).isEqualTo(media); String id = hls(); int fetched = mediaGets.get();
        if (change.equals("address")) rootPath = "/replacement/";
        ok(sourcePath, "PUT", sourceInput(change.equals("username") ? "other-reader" : "reader",
                change.equals("password") ? "changed-fixture-password" : "fixture-password"));
        if (change.equals("address")) scan();
        assertThat(original().body()).isEqualTo(media); assertThat(mediaGets.get()).isEqualTo(fetched + 1);
        assertThat(request("/api/v1/transcoding/sessions/" + id, "GET", null).statusCode()).isEqualTo(409);
    }

    @Test void scanBetweenSnapshotAndRemoteOpenDoesNotInvalidateTheCredentialSnapshot() throws Exception {
        source(); var snapshot = sources.get(songs.get(songId).sourceId());
        scan(); assertThat(sources.get(snapshot.id()).updatedAt()).isNotEqualTo(snapshot.updatedAt());
        // Exercise the exact pre-request race with the old immutable DTO and the current credential row.
        var remote = org.springframework.test.util.ReflectionTestUtils.invokeMethod(playback, "openRemote", snapshot,
                "song.mp4", "bytes=0-63", false, null);
        assertThat(remote).isInstanceOf(AutoCloseable.class); ((AutoCloseable) remote).close();
        assertThat(mediaGets.get()).isEqualTo(1);
    }

    @Test void credentialReplacementCannotMixANewPasswordIntoAnOldConnectionSnapshot() throws Exception {
        source(); var snapshot = sources.get(songs.get(songId).sourceId());
        ok(sourcePath, "PUT", sourceInput("reader", "replacement-fixture-password"));
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(playback, "openRemote", snapshot,
                "song.mp4", "bytes=0-63", false, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(failure -> assertThat(((org.springframework.web.server.ResponseStatusException) failure).getStatusCode().value()).isEqualTo(409));
        assertThat(mediaGets.get()).isZero();
    }

    @Test void anOriginalResponseAlreadyDownloadingSurvivesAnUnchangedRescan() throws Exception {
        source();
        var connected = new CountDownLatch(1); var proceed = new CountDownLatch(1);
        beforeMedia = () -> {
            connected.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test did not release the source response"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        try (var player = Executors.newSingleThreadExecutor()) {
            var download = player.submit(this::original);
            try {
                assertThat(connected.await(3, TimeUnit.SECONDS)).isTrue();
                scan(); proceed.countDown();
                var response = download.get(3, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEqualTo(media);
                assertThat(entries()).isEqualTo(1);
            } finally { proceed.countDown(); }
        }
    }

    private void upstream(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("PROPFIND")) {
            String path = exchange.getRequestURI().getPath();
            String props = "<d:resourcetype/>" + (sizeKnown ? "<d:getcontentlength>" + media.length + "</d:getcontentlength>" : "")
                    + (modified == null ? "" : "<d:getlastmodified>" + modified + "</d:getlastmodified>")
                    + (etag == null ? "" : "<d:getetag>" + etag.replace("\"", "&quot;") + "</d:getetag>");
            String xml = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" + entry(path, "<d:resourcetype><d:collection/></d:resourcetype>")
                    + entry(path + "song.mp4", props) + "</d:multistatus>";
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(207, bytes.length); exchange.getResponseBody().write(bytes); return;
        }
        beforeMedia.run(); mediaGets.incrementAndGet(); byte[] bytes = media;
        String range = exchange.getRequestHeaders().getFirst("Range"); int first = 0, last = bytes.length - 1;
        if (range != null) {
            var bounds = range.substring(6).split("-", -1); first = Integer.parseInt(bounds[0]);
            if (!bounds[1].isEmpty()) last = Math.min(last, Integer.parseInt(bounds[1]));
            exchange.getResponseHeaders().set("Content-Range", "bytes " + first + "-" + last + "/" + bytes.length);
        }
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if (etag != null) exchange.getResponseHeaders().set("ETag", etag);
        exchange.sendResponseHeaders(range == null ? 200 : 206, last - first + 1);
        exchange.getResponseBody().write(bytes, first, last - first + 1);
    }
    private String entry(String href, String props) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }
}
