package com.mynora.singverse.media;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "singverse.metadata.worker-enabled=false")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("sqlite")
class WebDavScanHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("webdav.db").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    HttpClient client;
    String profileId, selectedRuleId;
    static final String PARSER = "/api/v1/filename-parser";
    static final String SOURCES = "/api/v1/media-sources";

    @BeforeEach void reset() {
        db.update("DELETE FROM media_scan_song"); db.update("DELETE FROM media_scan_directory"); db.update("DELETE FROM media_scan_task");
        db.update("DELETE FROM song_metadata");
        db.update("DELETE FROM media_song"); db.update("DELETE FROM media_source");
        db.update("DELETE FROM filename_parser_config");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
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
    JsonNode workspace() throws Exception { return body(request(PARSER + "/profiles", "GET", null, false)); }
    void createProfile() throws Exception {
        var created = body(request(PARSER + "/rule-definitions", "POST", Map.of("revision", workspace().path("revision").asLong(),
                "definition", Map.of("name", "反向命名", "type", "TEMPLATE", "expression", "{title}-{artist}")), true));
        assertThat(created.has("changedId")).isTrue();
        String ruleId = created.path("changedId").asText();
        selectedRuleId = ruleId;
        var profile = request(PARSER + "/profiles", "POST", Map.of("revision", created.path("revision").asLong(),
                "profile", Map.of("name", "本地反向方案", "entries", List.of(Map.of("ruleId", ruleId, "enabled", true)))), true);
        assertThat(profile.statusCode()).isEqualTo(201);
        profileId = body(profile).path("changedId").asText();
    }
    String local(Path directory) throws Exception {
        var created = request(SOURCES, "POST", Map.of("name", "本地歌曲", "type", "LOCAL", "location", directory.toString(), "parserProfileId", profileId), true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).path("parserProfileId").asText()).isEqualTo(profileId);
        return SOURCES + "/" + body(created).path("id").asText();
    }

    @Autowired com.mynora.singverse.media.webdav.WebDavScanService scans;
    @Autowired com.mynora.singverse.media.webdav.ScanTaskMapper taskMapper;
    com.sun.net.httpserver.HttpServer dav;
    java.util.concurrent.ExecutorService serverThreads;
    final java.util.concurrent.CopyOnWriteArrayList<String> methods = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.concurrent.CopyOnWriteArrayList<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile java.util.function.Function<String, String> listing;
    volatile int remoteStatus = 207;
    volatile java.util.concurrent.CountDownLatch entered, release;
    @AfterEach void closeDav() {
        if (release != null) release.countDown();
        if (dav != null) dav.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
        assertThat(methods).allMatch("PROPFIND"::equals);
    }
    void server() throws Exception {
        dav = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = java.util.concurrent.Executors.newCachedThreadPool(); dav.setExecutor(serverThreads);
        dav.createContext("/", exchange -> {
            try {
                methods.add(exchange.getRequestMethod()); paths.add(exchange.getRequestURI().getPath());
                assertThat(exchange.getRequestHeaders().getFirst("Depth")).isEqualTo("1");
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("reader:example-test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(requestBody).contains("resourcetype", "getcontentlength", "getlastmodified").doesNotContain("allprop", "getcontenttype");
                if (entered != null) entered.countDown();
                if (release != null) release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                byte[] bytes = listing.apply(exchange.getRequestURI().getPath()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(remoteStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); dav.start();
    }
    String source() throws Exception {
        var response = request(SOURCES, "POST", Map.of("name", "OpenList", "type", "WEBDAV", "location", address(),
                "username", "reader", "password", "example-test-password", "parserProfileId", profileId), true);
        assertThat(response.statusCode()).isEqualTo(201);
        return SOURCES + "/" + body(response).path("id").asText();
    }
    String address() { return "http://127.0.0.1:" + dav.getAddress().getPort() + "/dav/歌曲/"; }
    String start(String source) throws Exception {
        var response = request(source + "/scan-tasks", "POST", null, true);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("Location")).isPresent();
        return source + "/scan-tasks/" + body(response).path("id").asText();
    }
    JsonNode await(String task) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(4).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode state = body(request(task, "GET", null, false));
            if (!List.of("QUEUED", "RUNNING").contains(state.path("status").asText())) return state;
            Thread.sleep(30);
        }
        throw new AssertionError("Task did not finish");
    }
    static String xml(String entries) { return "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:multistatus xmlns:d=\"DAV:\">" + entries + "</d:multistatus>"; }
    static String entry(String href, boolean folder) {
        return "<d:response><d:href>" + href.replace("&", "&amp;") + "</d:href><d:propstat><d:prop><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                + "<d:propstat><d:prop><d:getcontentlength/><d:getlastmodified/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>";
    }
    static String href(String path) {
        try { return new URI(null, null, path, null).toASCIIString(); } catch (Exception e) { throw new AssertionError(e); }
    }
    void basic() throws Exception { login(); createProfile(); server(); }
    @Test void audioFilesAreIndexedByFilenameWithoutDownloadingContents() throws Exception {
        basic();
        var extensions = List.of("MP3", "FLAC", "WAV", "M4A", "AAC", "OGG", "OGA", "OPUS", "WMA", "AIF", "AIFF", "APE");
        listing = path -> xml(entry(href(path), true) + extensions.stream()
                .map(extension -> entry(href("晴天-周杰伦." + extension), false)).collect(java.util.stream.Collectors.joining()) + entry("notes.txt", false));
        String source = source();
        var completed = await(start(source));
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("totalSongs").asInt()).isEqualTo(extensions.size());
        var page = body(request(source + "/songs?size=100", "GET", null, false));
        assertThat(page.path("items").size()).isEqualTo(extensions.size());
        for (var song : page.path("items")) {
            assertThat(song.at("/result/title").asText()).isEqualTo("晴天");
            assertThat(song.at("/result/needsReview").asBoolean()).isFalse();
        }
    }

    @Test void recursiveNamesOnlyWithEncodedPathsMissingAttributesStableIdsAndMetadataPreservation() throws Exception {
        basic();
        listing = path -> path.equals("/dav/歌曲/")
                ? xml(entry(href(path), true) + entry("子目录/", true) + entry("晴天-周杰伦.mkv", false) + entry("notes.txt", false) + entry("子目录/", true))
                : xml(entry(href(path), true) + entry("100%25%20%2B%252F-歌手.MP4", false));
        String source = source();
        assertThat(request(source + "/scan-tasks/latest", "GET", null, false).statusCode()).isEqualTo(204);
        JsonNode completed = await(start(source));
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("totalSongs").asInt()).isEqualTo(2);
        assertThat(completed.path("directories").asInt()).isEqualTo(2);
        JsonNode page = body(request(source + "/songs?size=100", "GET", null, false));
        assertThat(page.toString()).contains("子目录/100% +%2F-歌手.MP4");
        for (var song : page.path("items")) { assertThat(song.path("fileSize").isNull()).isTrue(); assertThat(song.path("modifiedAt").isNull()).isTrue(); }
        String id = page.at("/items/0/id").asText();
        // Fixed JDBC is test preparation only. A non-displayable metadata row still verifies retention.
        db.update("INSERT INTO song_metadata (song_id, source_id, fingerprint, track_json, fields_json, origins_json, applied_at) VALUES (?, ?, 'test', '{}', '[]', '{}', '2026-01-01T00:00:00Z')", id, source.substring(source.lastIndexOf('/') + 1));
        assertThat(await(start(source)).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM song_metadata WHERE song_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_scan_song", Integer.class)).isZero();
        assertThat(Collections.frequency(paths, "/dav/歌曲/子目录/")).isEqualTo(2);
        listing = path -> xml(entry(href(path), true) + entry("只有一首-歌手.mkv", false));
        assertThat(await(start(source)).path("totalSongs").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM song_metadata", Integer.class)).isZero();
    }
    @Test void failuresAndEmptyResultsNeverReplaceExistingIndex() throws Exception {
        basic(); listing = path -> xml(entry(href(path), true) + entry("晴天-周杰伦.mkv", false));
        String source = source(); assertThat(await(start(source)).path("status").asText()).isEqualTo("SUCCEEDED");
        String before = request(source + "/songs", "GET", null, false).body();
        for (int status : List.of(401, 403, 404, 302, 500)) {
            remoteStatus = status;
            assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED");
            assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
        }
        remoteStatus = 207;
        for (String bad : List.of(xml(entry("/dav/escape.mkv", false)), xml(entry("https://elsewhere.invalid/song.mkv", false)),
                xml(entry("../outside/", true)), "<invalid", "<!DOCTYPE d:multistatus [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><d:multistatus xmlns:d='DAV:'>&secret;</d:multistatus>")) {
            listing = path -> bad;
            JsonNode failed = await(start(source)); assertThat(failed.path("status").asText()).isEqualTo("FAILED");
            assertThat(failed.toString()).doesNotContain("example-test-password", "root:");
            assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
        }
        listing = path -> xml(entry(href(path), true));
        assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED");
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
    }
    @Test void cancellationConflictAndConfigurationChangesAreAtomic() throws Exception {
        basic(); listing = path -> xml(entry(href(path), true) + entry("晴天-周杰伦.mkv", false));
        String source = source(); assertThat(await(start(source)).path("status").asText()).isEqualTo("SUCCEEDED");
        String before = request(source + "/songs", "GET", null, false).body();
        entered = new java.util.concurrent.CountDownLatch(1); release = new java.util.concurrent.CountDownLatch(1);
        String task = start(source); assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(request(source + "/scan-tasks", "POST", null, true).statusCode()).isEqualTo(409);
        assertThat(request(task + "/cancel", "POST", null, false).statusCode()).isEqualTo(403);
        assertThat(request(task + "/cancel", "POST", null, true).statusCode()).isEqualTo(200);
        release.countDown(); assertThat(await(task).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
        assertThat(request(task + "/cancel", "POST", null, true).statusCode()).isEqualTo(409);
        entered = new java.util.concurrent.CountDownLatch(1); release = new java.util.concurrent.CountDownLatch(1);
        task = start(source); assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(request(source, "PUT", Map.of("name", "改过的来源", "type", "WEBDAV", "location", address(), "username", "reader", "parserProfileId", profileId), true).statusCode()).isEqualTo(200);
        release.countDown(); assertThat(await(task).path("status").asText()).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song", Integer.class)).isEqualTo(1);
    }
    @Test void restartRecoveryAndRetentionKeepLatestAndCleanStaging() throws Exception {
        basic(); String source = source(); String sourceId = source.substring(source.lastIndexOf('/') + 1);
        db.update("INSERT INTO media_scan_task (id, source_id, active_source, status, phase, created_at) VALUES ('old', ?, ?, 'RUNNING', 'SCANNING', '2020-01-01T00:00:00Z')", sourceId, sourceId);
        db.update("INSERT INTO media_scan_directory (task_id, path_hash, path, depth) VALUES ('old', 'hash', '/dav/', 0)");
        scans.recover();
        db.update("INSERT INTO media_scan_task (id, source_id, status, phase, created_at, finished_at) VALUES ('expired', ?, 'FAILED', 'SCANNING', '2019-01-01T00:00:00Z', '2019-01-01T01:00:00Z')", sourceId);
        taskMapper.prune("2026-01-01T00:00:00Z");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_scan_task", Integer.class)).isEqualTo(1);
        db.update("UPDATE media_scan_task SET finished_at='2020-01-01T01:00:00Z' WHERE id='old'");
        taskMapper.prune("2026-01-01T00:00:00Z");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_scan_task", Integer.class)).isEqualTo(1);
        assertThat(body(request(source + "/scan-tasks/latest", "GET", null, false)).path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_scan_directory", Integer.class)).isZero();
    }
    @Test void hundredThousandSongsUseStagingAndLimitFailurePreservesPublishedIndex() throws Exception {
        basic();
        java.util.concurrent.atomic.AtomicBoolean overflow = new java.util.concurrent.atomic.AtomicBoolean();
        listing = path -> {
            StringBuilder content = new StringBuilder(entry(href(path), true));
            if (path.equals("/dav/歌曲/")) for (int i = 0; i < 100; i++) content.append(entry("d" + i + "/", true));
            else {
                int count = overflow.get() && path.endsWith("d99/") ? 1001 : 1000;
                for (int i = 0; i < count; i++) content.append(entry("歌曲" + i + "-歌手.mkv", false));
            }
            return xml(content.toString());
        };
        String source = source();
        var completed = await(start(source));
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("totalSongs").asInt()).isEqualTo(100000);
        assertThat(body(request(source + "/songs?size=100&page=999", "GET", null, false)).path("items").size()).isEqualTo(100);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song", Integer.class)).isEqualTo(100000);
        overflow.set(true);
        var failed = await(start(source)); assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error").asText()).contains("10 万");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song", Integer.class)).isEqualTo(100000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_scan_song", Integer.class)).isZero();
    }
    @Test void endpointsRequireAdminCsrfAndMatchTaskToSource() throws Exception {
        assertThat(request(SOURCES + "/missing/scan-tasks/latest", "GET", null, false).statusCode()).isEqualTo(401);
        basic(); listing = path -> xml(entry(href(path), true) + entry("歌曲-歌手.mkv", false));
        String source = source();
        assertThat(request(source + "/scan-tasks", "POST", null, false).statusCode()).isEqualTo(403);
        String task = start(source); await(task);
        var other = body(request(SOURCES, "POST", Map.of("name", "其他来源", "type", "WEBDAV", "location", address() + "other/"), true));
        String otherPath = SOURCES + "/" + other.path("id").asText() + "/scan-tasks/" + task.substring(task.lastIndexOf('/') + 1);
        assertThat(request(otherPath, "GET", null, false).statusCode()).isEqualTo(404);
        assertThat(request(otherPath + "/cancel", "POST", null, true).statusCode()).isEqualTo(404);
    }
    @Test void onlyTwoWorkersRunAndQueuedCancellationIsImmediate() throws Exception {
        basic(); listing = path -> xml(entry(href(path), true) + entry("歌曲-歌手.mkv", false));
        entered = new java.util.concurrent.CountDownLatch(2); release = new java.util.concurrent.CountDownLatch(1);
        List<String> sources = new ArrayList<>(); List<String> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var created = body(request(SOURCES, "POST", Map.of("name", "来源" + i, "type", "WEBDAV", "location", address() + i + "/",
                    "username", "reader", "password", "example-test-password"), true));
            sources.add(SOURCES + "/" + created.path("id").asText());
            tasks.add(start(sources.getLast()));
        }
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(body(request(tasks.get(2), "GET", null, false)).path("status").asText()).isEqualTo("QUEUED");
        assertThat(request(tasks.get(2) + "/cancel", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(await(tasks.get(2)).path("status").asText()).isEqualTo("CANCELLED");
        release.countDown();
        for (String task : tasks.subList(0, 2)) assertThat(await(task).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(paths).hasSize(2);
    }
    @Test void publishDatabaseFailureRollsBackAndDeepDirectoryFailsWithoutPartialIndex() throws Exception {
        basic(); listing = path -> xml(entry(href(path), true) + entry("歌曲-歌手.mkv", false));
        String source = source(); assertThat(await(start(source)).path("status").asText()).isEqualTo("SUCCEEDED");
        String before = request(source + "/songs", "GET", null, false).body();
        boolean sqlite;
        try (var connection = db.getDataSource().getConnection()) { sqlite = connection.getMetaData().getDatabaseProductName().equals("SQLite"); }
        db.execute(sqlite ? "CREATE TRIGGER fail_webdav_insert BEFORE INSERT ON media_song BEGIN SELECT RAISE(ABORT, 'test insert failure'); END"
                : "CREATE TRIGGER fail_webdav_insert BEFORE INSERT ON media_song FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test insert failure'");
        try { assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED"); }
        finally { db.execute("DROP TRIGGER fail_webdav_insert"); }
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
        listing = path -> xml(entry(href(path), true) + entry("child/", true) + entry("歌曲-歌手.mkv", false));
        assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED");
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
    }

}
