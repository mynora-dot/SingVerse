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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"singverse.metadata.worker-enabled=false", "singverse.webdav.request-interval-ms=0", "singverse.webdav.metadata-worker-enabled=false","singverse.webdav.share-worker-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("sqlite")
class WebDavScanHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("webdav.db").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav-assets").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.transaction.PlatformTransactionManager shareTransactions;
    @Autowired ObjectMapper json;
    HttpClient client;
    String profileId, selectedRuleId;
    static final String PARSER = "/api/v1/filename-parser";
    static final String SOURCES = "/api/v1/media-sources";

    @BeforeEach void reset() {
        db.update("DELETE FROM media_scan_song"); db.update("DELETE FROM media_scan_directory"); db.update("DELETE FROM media_scan_task");
        db.update("DELETE FROM webdav_share_receipt");db.update("DELETE FROM webdav_share_asset");db.update("DELETE FROM webdav_share_entry");db.update("DELETE FROM webdav_share_job");db.update("DELETE FROM webdav_metadata_task"); db.update("DELETE FROM webdav_resource"); db.update("DELETE FROM webdav_metadata_source"); db.update("DELETE FROM webdav_access_budget");
        db.update("DELETE FROM media_metadata_file");
        db.update("DELETE FROM song_metadata");
        db.update("DELETE FROM media_song"); db.update("DELETE FROM media_source");
        db.update("DELETE FROM filename_parser_config");
        db.update("UPDATE compute_node_settings SET mode='DISABLED'");
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

    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavMetadataService embedded;
    @Autowired com.mynora.singverse.media.metadata.MetadataFileService metadataFiles;
    @Autowired com.mynora.singverse.media.metadata.MetadataFileMapper metadataFileMapper;
    @Autowired com.mynora.singverse.media.metadata.MetadataMapper appliedMapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean com.mynora.singverse.media.metadata.MetadataCoverService fileCovers;
    @Autowired com.mynora.singverse.media.webdav.WebDavScanService scans;
    @Autowired com.mynora.singverse.media.webdav.ScanTaskMapper taskMapper;
    com.sun.net.httpserver.HttpServer dav;
    java.util.concurrent.ExecutorService serverThreads;
    final java.util.concurrent.CopyOnWriteArrayList<String> methods = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.concurrent.CopyOnWriteArrayList<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile java.util.function.Function<String, String> listing;
    volatile int remoteStatus = 207;
    volatile boolean allowMediaRequests;
    volatile int mediaStatus=206;
    volatile String mediaRedirect;
    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavAccess access;
    @Autowired com.mynora.singverse.media.repository.MediaSongRepository indexedSongs;
    volatile String mediaEtag="\"version-1\"";
    final byte[] mediaBytes="example-media-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavMetadataInput metadataInput;
    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavMetadataMapper metadataMapper;
    @Autowired com.mynora.singverse.compute.ComputeNodeSettings computeSettings;
    @Autowired com.mynora.singverse.media.service.MediaSourceService sourceService;
    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavShareService sharing;
    @Autowired com.mynora.singverse.media.webdav.metadata.WebDavShareJobs shareJobs;
    volatile java.util.concurrent.CountDownLatch entered, release;
    @AfterEach void closeDav() {
        if (release != null) release.countDown();
        if (dav != null) dav.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
        assertThat(methods).allMatch(method -> method.equals("PROPFIND") || allowMediaRequests && method.equals("GET"));
    }
    void server() throws Exception {
        dav = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = java.util.concurrent.Executors.newCachedThreadPool(); dav.setExecutor(serverThreads);
        dav.createContext("/", exchange -> {
            try {
                methods.add(exchange.getRequestMethod()); paths.add(exchange.getRequestURI().getPath());
                if (allowMediaRequests && exchange.getRequestMethod().equals("GET")) {
                    if(mediaStatus!=206) {
                        if(mediaStatus==429)exchange.getResponseHeaders().add("Retry-After","86400");
                        if(mediaRedirect!=null)exchange.getResponseHeaders().add("Location",mediaRedirect);
                        exchange.sendResponseHeaders(mediaStatus,0);return;
                    }
                    String condition=exchange.getRequestHeaders().getFirst("If-Match");
                    if(condition!=null&&!condition.equals(mediaEtag)){exchange.sendResponseHeaders(412,-1);return;}
                    String[] bounds=exchange.getRequestHeaders().getFirst("Range").substring(6).split("-");
                    int first=Integer.parseInt(bounds[0]),last=Math.min(Integer.parseInt(bounds[1]),mediaBytes.length-1);
                    exchange.getResponseHeaders().add("Content-Range","bytes "+first+"-"+last+"/"+mediaBytes.length);
                    exchange.getResponseHeaders().add("ETag",mediaEtag);
                    exchange.sendResponseHeaders(206,last-first+1);exchange.getResponseBody().write(java.util.Arrays.copyOfRange(mediaBytes,first,last+1));return;
                }
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
        assertThat(db.queryForObject("SELECT COUNT(*) FROM song_metadata", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT missing_scans FROM webdav_resource WHERE song_id=?",Integer.class,id)).isEqualTo(1);
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
        assertThat(await(start(source)).path("status").asText()).isEqualTo("SUCCEEDED");
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
            embedded.configure(created.path("id").asText(),false,false,"test-group-"+i,List.of());
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
        listing = path -> xml(entry(href(path), true) + entry("新增-歌手.mkv", false));
        try { assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED"); }
        finally { db.execute("DROP TRIGGER fail_webdav_insert"); }
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
        listing = path -> xml(entry(href(path), true) + entry("child/", true) + entry("歌曲-歌手.mkv", false));
        assertThat(await(start(source)).path("status").asText()).isEqualTo("FAILED");
        assertThat(request(source + "/songs", "GET", null, false).body()).isEqualTo(before);
    }

    @Test void deletionRequiresSeparatedCompleteScansAndRestoresCachedTags() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false)+entry("删除-歌手.mp3",false));
        String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        String id=db.queryForObject("SELECT id FROM media_song WHERE relative_path=?",String.class,"删除-歌手.mp3");
        String tags="{\"title\":\"缓存歌名\",\"artists\":[\"缓存歌手\"]}";
        db.update("UPDATE webdav_resource SET tags_json=?,state='COMPLETE' WHERE song_id=?",tags,id);
        db.update("UPDATE media_song SET embedded_tags_json=? WHERE id=?",tags,id);
        listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false));
        var first=await(start(source));assertThat(first.path("missingSongs").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song WHERE id=?",Integer.class,id)).isEqualTo(1);
        await(start(source));assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song WHERE id=?",Integer.class,id)).isEqualTo(1);
        db.update("UPDATE webdav_resource SET missing_since=? WHERE song_id=?",java.time.Instant.now().minusSeconds(1801).toString(),id);
        var removed=await(start(source));assertThat(removed.path("removedSongs").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song WHERE id=?",Integer.class,id)).isZero();
        listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false)+entry("删除-歌手.mp3",false));await(start(source));
        assertThat(db.queryForObject("SELECT embedded_tags_json FROM media_song WHERE id=?",String.class,id)).isEqualTo(tags);
        assertThat(db.queryForObject("SELECT state FROM webdav_resource WHERE song_id=?",String.class,id)).isEqualTo("COMPLETE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isZero();
    }
    @Test void emptyLibraryNeedsExplicitConfirmationAfterTwoObservations() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mkv",false));String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        listing=path->xml(entry(href(path),true));await(start(source));
        assertThat(request(source+"/embedded-metadata/confirm-empty","POST",null,true).statusCode()).isEqualTo(409);
        db.update("UPDATE webdav_metadata_source SET empty_since=? WHERE source_id=?",java.time.Instant.now().minusSeconds(1801).toString(),sid);
        await(start(source));assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song",Integer.class)).isEqualTo(1);
        assertThat(request(source+"/embedded-metadata/confirm-empty","POST",null,true).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_resource WHERE archived_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void extractionIsOptInAndRefreshCoalescesAndUsesNewGeneration() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        String id=db.queryForObject("SELECT id FROM media_song",String.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isZero();
        assertThat(request(source+"/embedded-metadata","PUT",Map.of("enabled",true,"paused",false),false).statusCode()).isEqualTo(403);
        embedded.configure(sid,true,false,null,null);
        embedded.refresh(List.of(id),false);embedded.refresh(List.of(id),false);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isEqualTo(1);
        db.update("UPDATE webdav_resource SET state='EMPTY' WHERE song_id=?",id);db.update("UPDATE webdav_metadata_task SET state='EMPTY'");
        await(start(source));assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isEqualTo(1);
        embedded.refresh(List.of(id),true);embedded.refresh(List.of(id),true);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT generation FROM webdav_resource",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task WHERE deep=1",Integer.class)).isEqualTo(1);
    }
    Map<String,Object> prepareRead(String source) {
        String sid=source.substring(source.lastIndexOf('/')+1);embedded.configure(sid,true,false,null,null);
        var task=metadataMapper.pending(Map.of("now",java.time.Instant.now().toString(),"day",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString())).getFirst();
        metadataInput.authorize(task,"test-internal-token-abcdefghijklmnopqrstuvwxyz",false);allowMediaRequests=true;return task;
    }
    HttpResponse<String> read(Map<String,Object> task,String range,String token)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/internal/metadata-input/"+task.get("id")))
            .header("Authorization","Bearer "+token).header("Range",range).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void metadataInputRejectsWrongTokenAndPinsVersionAndRequestBudget() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));var task=prepareRead(source);
        String token="test-internal-token-abcdefghijklmnopqrstuvwxyz";
        assertThat(read(task,"bytes=0-3","wrong-token").statusCode()).isEqualTo(401);
        assertThat(read(task,"bytes=0-1048576",token).statusCode()).isEqualTo(400);
        assertThat(read(task,"bytes=0-3",token).body()).isEqualTo("exam");
        mediaEtag="\"version-2\"";assertThat(read(task,"bytes=4-7",token).statusCode()).isEqualTo(409);
        mediaEtag="\"version-1\"";
        for(int i=0;i<14;i++)assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(206);
        assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(413);
        assertThat(metadataInput.requests(task.get("id").toString())).isEqualTo(16);
        metadataInput.revoke(task.get("id").toString());assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(401);
    }
    @Test void ignoredRangeStopsWithoutBodyAndRateLimitPersistsLongerRetryAfter() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));var task=prepareRead(source);String token="test-internal-token-abcdefghijklmnopqrstuvwxyz";
        mediaStatus=200;assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(422);
        assertThat(metadataInput.bytes(task.get("id").toString())).isZero();
        mediaStatus=429;assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(429);
        String blocked=db.queryForObject("SELECT blocked_until FROM webdav_access_budget",String.class);
        assertThat(java.time.Instant.parse(blocked)).isAfter(java.time.Instant.now().plusSeconds(86000));
        access.block(source.substring(source.lastIndexOf('/')+1),false,java.time.Instant.now().plusSeconds(21600));
        assertThat(db.queryForObject("SELECT blocked_until FROM webdav_access_budget",String.class)).isEqualTo(blocked);
        assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(429);
    }
    @Test void workerPublishesOnceAndRegularScansMakeNoMediaReads() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        var compute=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean rebind=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean invalidCover=new java.util.concurrent.atomic.AtomicBoolean();
        var coverBytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",coverBytes);
        String validCover=Base64.getEncoder().encodeToString(coverBytes.toByteArray());
        compute.createContext("/internal/v1/status",exchange->{byte[] bytes="{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[\"media-remote-tags\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        compute.createContext("/internal/v1/media/remote-tags",exchange->{
            try{calls.incrementAndGet();var payload=json.readTree(exchange.getRequestBody());
                assertThat(payload.has("url")).isFalse();assertThat(payload.has("password")).isFalse();
                var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/internal/metadata-input/"+payload.path("reference").asText()))
                    .header("Authorization",exchange.getRequestHeaders().getFirst("Authorization")).header("Range","bytes=0-3").GET().build();
                assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(206);
                if(rebind.get())sourceService.update(sid,new com.mynora.singverse.media.dto.MediaSourceDtos.Input("重新绑定的来源","WEBDAV",address()+"replacement/","reader","example-test-password",profileId,false,null));
                byte[] bytes=json.writeValueAsBytes(Map.of("state","COMPLETE","tags",Map.of("title","真实文件标签","artists",List.of("内嵌歌手")),"fields",Map.of("title","PRESENT"),"technical",Map.of("duration","12.5"),"extractorVersion","remote-tags-1","cover",invalidCover.get()?"YWJj":validCover));
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}
        });compute.start();allowMediaRequests=true;
        try{
            computeSettings.save(new com.mynora.singverse.compute.ComputeNodeDtos.Save("MANUAL","http://127.0.0.1:"+compute.getAddress().getPort(),"test-internal-token-abcdefghijklmnopqrstuvwxyz",false,List.of(),computeSettings.view().revision()));
            embedded.configure(sid,true,false,null,null);embedded.runNext();
            assertThat(db.queryForObject("SELECT state FROM webdav_resource",String.class)).isEqualTo("COMPLETE");
            assertThat(request(source+"/songs","GET",null,false).body()).contains("真实文件标签","内嵌歌手","12500");
            long mediaReads=methods.stream().filter("GET"::equals).count();
            await(start(source));await(start(source));embedded.runNext();
            assertThat(calls.get()).isEqualTo(1);assertThat(methods.stream().filter("GET"::equals).count()).isEqualTo(mediaReads);
            String song=db.queryForObject("SELECT id FROM media_song",String.class);
            assertThat(request("/api/v1/media-songs/"+song+"/embedded-metadata/cover","GET",null,false).headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
            embedded.refresh(List.of(song),false);db.update("UPDATE webdav_access_budget SET next_file_at=NULL");invalidCover.set(true);embedded.runNext();
            assertThat(db.queryForObject("SELECT state FROM webdav_resource",String.class)).isEqualTo("PARTIAL");
            assertThat(json.readTree(db.queryForObject("SELECT result_json FROM webdav_resource",String.class)).path("state").asText()).isEqualTo("PARTIAL");
            embedded.refresh(List.of(song),false);
            db.update("UPDATE webdav_access_budget SET next_file_at=NULL");rebind.set(true);embedded.runNext();
            assertThat(calls.get()).isEqualTo(3);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_resource",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isZero();
        }finally{compute.stop(0);}
    }

    @Test void redirectCredentialsAreStrippedAndPrivateTargetsRequireExplicitOrigin() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));var task=prepareRead(source);
        var target=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger downloads=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> credential=new java.util.concurrent.atomic.AtomicReference<>();
        target.createContext("/file",exchange->{downloads.incrementAndGet();credential.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.getResponseHeaders().add("Content-Range","bytes 0-3/4");exchange.getResponseHeaders().add("ETag","\"redirect-file\"");exchange.sendResponseHeaders(206,4);exchange.getResponseBody().write("data".getBytes());exchange.close();});target.start();
        try{
            String origin="http://127.0.0.1:"+target.getAddress().getPort();mediaStatus=302;mediaRedirect=origin+"/file?temporary=example-test";
            String token="test-internal-token-abcdefghijklmnopqrstuvwxyz";
            assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(502);assertThat(downloads.get()).isZero();
            String sid=source.substring(source.lastIndexOf('/')+1);embedded.configure(sid,true,false,null,List.of(origin));task=prepareRead(source);
            var response=read(task,"bytes=0-3",token);assertThat(response.statusCode()).isEqualTo(206);assertThat(response.body()).isEqualTo("data");
            assertThat(downloads.get()).isEqualTo(1);assertThat(credential.get()).isNull();assertThat(metadataInput.requests(task.get("id").toString())).isEqualTo(2);
            assertThat(embedded.detail(task.get("song_id").toString()).toString()).doesNotContain("temporary=",origin,"example-test-password");
            mediaRedirect=URI.create(address()+"loop.mp3").toASCIIString();task=prepareRead(source);
            assertThat(read(task,"bytes=0-3",token).statusCode()).isEqualTo(502);assertThat(metadataInput.requests(task.get("id").toString())).isEqualTo(4);
        }finally{target.stop(0);}
    }
    @Test void dailyCountersDoNotLimitTasksOrContentReads() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));var task=prepareRead(source);
        db.update("UPDATE webdav_access_budget SET bytes_read=1099511627776,files_started=100000");
        String now=java.time.Instant.now().toString();
        assertThat(metadataMapper.pending(Map.of("now",now))).isNotEmpty();
        String group=task.get("access_group").toString();
        assertThat(metadataMapper.fileStart(Map.of("group",group,"now",now,"after",java.time.Instant.now().plusSeconds(10).toString()))).isEqualTo(1);
        assertThat(read(task,"bytes=0-3","test-internal-token-abcdefghijklmnopqrstuvwxyz").statusCode()).isEqualTo(206);
        assertThat(metadataInput.bytes(task.get("id").toString())).isEqualTo(4);
        assertThat(db.queryForObject("SELECT bytes_read FROM webdav_access_budget",Long.class)).isEqualTo(1099511627780L);
        assertThat(db.queryForObject("SELECT files_started FROM webdav_access_budget",Long.class)).isEqualTo(100001L);
        assertThat(metadataMapper.pending(Map.of("now",now))).isEmpty();
        assertThat(metadataMapper.fileStart(Map.of("group",group,"now",now,"after",now))).isZero();
    }
    @Test void directoryAttributeChangesOnlyRaiseHintAndKeepContentFingerprint() throws Exception {
        basic();
        java.util.function.Function<Integer,String> item=size->entry("歌曲-歌手.mp3",false).replace("<d:getcontentlength/><d:getlastmodified/>","<d:getcontentlength>"+size+"</d:getcontentlength><d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified><d:getetag>\"version-"+size+"\"</d:getetag>").replace("HTTP/1.1 404 Not Found","HTTP/1.1 200 OK");
        listing=path->xml(entry(href(path),true)+item.apply(100));String source=source();await(start(source));String id=db.queryForObject("SELECT id FROM media_song",String.class);
        String fingerprint=com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(indexedSongs.get(id));
        db.update("UPDATE webdav_resource SET state='EMPTY'");
        listing=path->xml(entry(href(path),true)+item.apply(200));await(start(source));
        assertThat(db.queryForObject("SELECT changed FROM webdav_resource",Integer.class)).isEqualTo(1);
        assertThat(indexedSongs.get(id).fileSize()).isEqualTo(200);
        assertThat(com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(indexedSongs.get(id))).isEqualTo(fingerprint);
        assertThat(db.queryForObject("SELECT state FROM webdav_resource",String.class)).isEqualTo("EMPTY");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isZero();
    }

    @Test void requestedRefreshIsNotHeldBehindAutomaticBacklog() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("Old-Artist.mp3",false)+entry("New-Artist.flac",false));String source=source();await(start(source));
        String sid=source.substring(source.lastIndexOf('/')+1);embedded.configure(sid,true,false,null,null);
        String selected=db.queryForObject("SELECT id FROM media_song WHERE relative_path='New-Artist.flac'",String.class);
        embedded.refresh(List.of(selected),false);
        var tasks=metadataMapper.pending(Map.of("now",java.time.Instant.now().toString()));
        assertThat(tasks).hasSize(2);assertThat(tasks.getFirst().get("song_id")).isEqualTo(selected);
        assertThat(((Number)tasks.getFirst().get("manual")).intValue()).isEqualTo(1);
    }

    @Test void archivedRunningExtractionCanResumeWhenPathReturns() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false)+entry("消失-歌手.mp3",false));String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        embedded.configure(sid,true,false,null,null);
        String id=db.queryForObject("SELECT id FROM media_song WHERE relative_path=?",String.class,"消失-歌手.mp3");
        db.update("UPDATE webdav_resource SET state='RUNNING' WHERE song_id=?",id);db.update("UPDATE webdav_metadata_task SET state='RUNNING' WHERE song_id=?",id);
        listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false));await(start(source));
        db.update("UPDATE webdav_resource SET missing_since=? WHERE song_id=?",java.time.Instant.now().minusSeconds(1801).toString(),id);await(start(source));
        assertThat(db.queryForObject("SELECT state FROM webdav_resource WHERE song_id=?",String.class,id)).isEqualTo("PENDING");
        db.update("UPDATE webdav_metadata_task SET state='SUPERSEDED' WHERE song_id=?",id);
        listing=path->xml(entry(href(path),true)+entry("保留-歌手.mp3",false)+entry("消失-歌手.mp3",false));await(start(source));
        assertThat(db.queryForObject("SELECT state FROM webdav_metadata_task WHERE song_id=?",String.class,id)).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task WHERE song_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void portableIndexRoundTripUsesRecipientRootAndNeverDownloadsMedia() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String origin=source();await(start(origin));String originalId=origin.substring(origin.lastIndexOf('/')+1);
        String snapshot="{\"state\":\"COMPLETE\",\"tags\":{\"title\":\"共享歌名\",\"artists\":[\"共享歌手\"]},\"aliases\":[\"Shared alias\"],\"technical\":{\"duration\":\"1.25\"},\"lyrics\":{\"text\":\"[00:01.00]歌词\",\"timing\":\"lrc\"},\"fields\":{\"title\":\"PRESENT\"}}";
        db.update("UPDATE webdav_resource SET result_json=?,state='COMPLETE' WHERE source_id=?",snapshot,originalId);
        metadataFiles.runNext();
        String exportId=shareJobs.create(originalId,null).get("id").toString();shareJobs.runNext();byte[] pack=Files.readAllBytes(shareJobs.part(originalId,exportId,1));
        var files=new HashMap<String,String>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(pack),java.nio.charset.StandardCharsets.UTF_8)){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)files.put(entry.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        assertThat(files.keySet()).contains("manifest.json","歌曲-歌手.mp3.strm","歌曲-歌手.mp3.nfo","歌曲-歌手.mp3.lrc");
        assertThat(files.toString()).contains("共享歌名","replace.invalid").doesNotContain("example-test-password",address(),originalId);
        var recipient=sourceService.create(new com.mynora.singverse.media.dto.MediaSourceDtos.Input("接收方","WEBDAV",address()+"recipient/","reader","recipient-test-password",profileId,false,null));
        var result=sharing.importBundle(recipient.id(),pack);assertThat(result.get("imported")).isEqualTo(1);
        assertThat(request(SOURCES+"/"+recipient.id()+"/songs","GET",null,false).body()).contains("共享歌名","共享歌手");
        assertThat(db.queryForObject("SELECT state FROM webdav_resource WHERE source_id=?",String.class,recipient.id())).isEqualTo("IMPORTED");
        assertThat(sharing.importBundle(recipient.id(),pack).get("skipped")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task WHERE source_id=?",Integer.class,recipient.id())).isZero();
        String recipientSong=db.queryForObject("SELECT id FROM media_song WHERE source_id=?",String.class,recipient.id());
        var imported=indexedSongs.get(recipientSong);
        assertThat(imported.metadata().fields()).contains("durationMs");
        assertThat(imported.metadata().track().durationMs()).isEqualTo(1250);
        assertThat(imported.metadata().track().aliases()).containsExactly("Shared alias");
        var corrected=imported.metadata().track();
        var manual=new com.mynora.singverse.media.metadata.ExternalTrack(null,null,"人工歌名",List.of("人工歌手"),null,2000,null,List.of(),null,"UNKNOWN",corrected.fetchedAt());
        String origins="{\"title\":{\"kind\":\"MANUAL\"},\"artists\":{\"kind\":\"PLATFORM\"},\"durationMs\":{\"kind\":\"MANUAL\"}}";
        db.update("INSERT INTO song_metadata(song_id,source_id,fingerprint,track_json,fields_json,origins_json,applied_at) VALUES(?,?,?,?,?,?,?)",recipientSong,recipient.id(),com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(imported),json.writeValueAsString(manual),"[\"title\",\"artists\",\"durationMs\"]",origins,"2026-10-31T00:00:00Z");
        var effective=indexedSongs.get(recipientSong).metadata();
        assertThat(effective.track().title()).isEqualTo("人工歌名");
        assertThat(effective.track().artists()).containsExactly("人工歌手");
        assertThat(effective.track().durationMs()).isEqualTo(2000);
        assertThat(effective.origins().get("title").kind()).isEqualTo("MANUAL");
        assertThat(effective.origins().get("artists").kind()).isEqualTo("PLATFORM");
        var unsafe=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(unsafe)){zip.putNextEntry(new java.util.zip.ZipEntry("../manifest.json"));zip.write("{}".getBytes());zip.closeEntry();}
        assertThatThrownBy(()->sharing.importBundle(recipient.id(),unsafe.toByteArray())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(methods).allMatch("PROPFIND"::equals);
        assertThat(request(origin+"/embedded-metadata/share-jobs/"+exportId+"/index","GET",null,false).statusCode()).isEqualTo(200);
    }

    @Test void hundredThousandSongsExportInBoundedResumableParts() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("歌曲-歌手.mp3",false));String source=source();await(start(source));String sid=source.substring(source.lastIndexOf('/')+1);
        String template=db.queryForObject("SELECT id FROM media_song",String.class);
        List<Object[]> rows=new ArrayList<>();
        for(int n=1;n<100000;n++)rows.add(new Object[]{UUID.randomUUID().toString(),"song-"+n+".mp3",UUID.randomUUID().toString(),template});
        new org.springframework.transaction.support.TransactionTemplate(shareTransactions).executeWithoutResult(t->db.batchUpdate("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,selected_rule_id,result_json,needs_review,embedded_tags_json) SELECT ?,source_id,?,?,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,selected_rule_id,result_json,needs_review,embedded_tags_json FROM media_song WHERE id=?",rows));
        metadataFiles.runNext();var created=shareJobs.create(sid,null);String id=created.get("id").toString();
        shareJobs.runNext();var result=shareJobs.get(sid,id);
        assertThat(result.get("state")).isEqualTo("SUCCEEDED");assertThat(((Number)result.get("total_count")).intValue()).isEqualTo(100000);
        assertThat(((Number)result.get("processed_count")).intValue()).isEqualTo(100000);
        assertThat(((Number)result.get("part_count")).intValue()).isEqualTo(20);
        var recipient=sourceService.create(new com.mynora.singverse.media.dto.MediaSourceDtos.Input("接收测试","WEBDAV",address()+"large-recipient/","reader","recipient-test",profileId,false,null));
        byte[] pack=Files.readAllBytes(shareJobs.part(sid,id,1));
        assertThat(shareJobs.importPart(recipient.id(),pack).get("imported")).isEqualTo(5000);
        assertThat(shareJobs.importPart(recipient.id(),pack).get("imported")).isEqualTo(5000);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song WHERE source_id=?",Integer.class,recipient.id())).isEqualTo(5000);
        db.update("DELETE FROM media_song WHERE id=?",template);
        String delta=shareJobs.create(sid,id).get("id").toString();shareJobs.runNext();
        assertThat(((Number)shareJobs.get(sid,delta).get("part_count")).intValue()).isZero();
        assertThat(Files.readString(shareJobs.deletions(sid,delta,1))).contains("advisoryOnly");
        assertThat(methods).allMatch("PROPFIND"::equals);
    }

    @Test void manualTrialDoesNotEnableAutomaticLibraryExtraction() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("one-artist.mp3",false)+entry("two-artist.mp3",false));String source=source();await(start(source));
        String id=db.queryForObject("SELECT id FROM media_song ORDER BY id LIMIT 1",String.class);
        embedded.refresh(List.of(id),false);
        assertThat(db.queryForObject("SELECT enabled FROM webdav_metadata_source",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM webdav_metadata_task",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT manual FROM webdav_metadata_task",Integer.class)).isEqualTo(1);
    }

    @Test void localManualEditsWriteManagedFilesAndExportLatestWithoutChangingOriginal() throws Exception {
        login();createProfile();Path directory=Files.createTempDirectory(temp,"local-files-");
        Path original=directory.resolve("Original-Artist.mp3");Files.writeString(original,"read-only fixture");
        String source=local(directory);assertThat(request(source+"/scan","POST",null,true).statusCode()).isEqualTo(200);
        String sid=source.substring(source.lastIndexOf('/')+1),id=db.queryForObject("SELECT id FROM media_song WHERE source_id=?",String.class,sid);
        metadataFiles.runNext();Path managed=metadataFiles.directory(sid,id);
        assertThat(Files.readString(managed.resolve("media.strm"))).isEqualTo(original.toRealPath()+"\n");
        assertThat(Files.readString(managed.resolve("media.nfo"))).contains("Original");
        String review="/api/v1/metadata/songs/"+id+"/review";
        var context=body(request(review,"GET",null,false));
        var input=Map.of("requestId",UUID.randomUUID().toString(),"fingerprint",context.path("fingerprint").asText(),"version",context.path("version").asText(),"fields",List.of("title","artists","album","releaseDate","durationMs","aliases"),"edits",Map.of("aliases",List.of("Edited alias"),"title","Edited & Saved","artists",List.of("New Artist"),"album","New Album","releaseDate","2026-10-31","durationMs",125000));
        assertThat(request(review+"/apply","POST",input,true).statusCode()).isEqualTo(200);
        assertThat(metadataFiles.pending(sid)).isEqualTo(1);
        metadataFiles.runNext();
        assertThat(metadataFiles.pending(sid)).isZero();
        assertThat(Files.readString(managed.resolve("media.nfo"))).contains("Edited &amp; Saved","New Artist","New Album","2026-10-31");
        assertThat(Files.readString(managed.resolve("metadata.json"))).contains("MANUAL","Edited & Saved","125.0","Edited alias");
        assertThat(Files.readString(original)).isEqualTo("read-only fixture");
        try(var children=Files.list(directory)){assertThat(children.count()).isEqualTo(1);}
        String exportId=shareJobs.create(sid,null).get("id").toString();shareJobs.runNext();
        try(var zip=new java.util.zip.ZipInputStream(Files.newInputStream(shareJobs.part(sid,exportId,1)))) {
            java.util.zip.ZipEntry entry;boolean verified=false;
            while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("manifest.json")) {
                String manifest=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(manifest).contains("Edited & Saved","New Album","Edited alias").doesNotContain(directory.toString());verified=true;
            }
            assertThat(verified).isTrue();
        }
        long revision=db.queryForObject("SELECT revision FROM media_metadata_file WHERE song_id=?",Long.class,id);
        new org.springframework.transaction.support.TransactionTemplate(shareTransactions).executeWithoutResult(t->{
            appliedMapper.deleteApplied(id);t.setRollbackOnly();
        });
        assertThat(db.queryForObject("SELECT revision FROM media_metadata_file WHERE song_id=?",Long.class,id)).isEqualTo(revision);
        assertThat(indexedSongs.get(id).metadata().track().title()).isEqualTo("Edited & Saved");
    }

    @Test void failedFileWriteRetainsDurableQueueAndCanResume() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("One-Artist.mp3",false));String source=source();await(start(source));
        String sid=source.substring(source.lastIndexOf('/')+1),id=db.queryForObject("SELECT id FROM media_song",String.class);
        Path parent=metadataFiles.directory(sid,id).getParent();Files.createDirectories(parent.getParent());Files.writeString(parent,"blocked");
        metadataFiles.runNext();
        assertThat(metadataFiles.status(sid).get("failed")).isEqualTo(1);
        String job=shareJobs.create(sid,null).get("id").toString();
        assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("WAITING");
        metadataFiles.runNext();
        shareJobs.runNext();assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("FAILED");
        Files.delete(parent);shareJobs.resume(sid,job);metadataFiles.runNext();
        assertThat(metadataFiles.pending(sid)).isZero();
        assertThat(Files.readString(metadataFiles.directory(sid,id).resolve("media.strm"))).contains("replace.invalid").doesNotContain(address(),"password");
        shareJobs.runNext();assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("SUCCEEDED");
        assertThat(methods).allMatch("PROPFIND"::equals);
    }

    @Test void exportWaitsForSavedFilesWithoutBlockingOtherLibraries() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("One-Artist.mp3",false));String source=source();await(start(source));
        String sid=source.substring(source.lastIndexOf('/')+1),job=shareJobs.create(sid,null).get("id").toString();
        shareJobs.runNext();assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("WAITING");
        var other=sourceService.create(new com.mynora.singverse.media.dto.MediaSourceDtos.Input("Ready library","WEBDAV",address()+"other/","reader","test-password",profileId,false,null));
        String otherJob=shareJobs.create(other.id(),null).get("id").toString();shareJobs.runNext();
        assertThat(shareJobs.get(other.id(),otherJob).get("state")).isEqualTo("SUCCEEDED");
        metadataFiles.runNext();shareJobs.runNext();assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("SUCCEEDED");
        assertThat(methods).allMatch("PROPFIND"::equals);
    }

    byte[] outerSet(byte[] first,byte[] second,boolean invalidTail)throws Exception {
        var out=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(out)){
            zip.putNextEntry(new java.util.zip.ZipEntry("index.json"));zip.write("{\"format\":\"singverse-share-set\",\"version\":2,\"parts\":[\"part-000001.zip\",\"part-000002.zip\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("part-000001.zip"));zip.write(first);zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry(invalidTail?"../part-000002.zip":"part-000002.zip"));zip.write(second);zip.closeEntry();
        }return out.toByteArray();
    }
    byte[] oneSharedSong(String path)throws Exception {
        var doc=json.createObjectNode();doc.put("format","singverse-media-index");doc.put("version",2);
        var entry=doc.putArray("entries").addObject();entry.put("path",path);entry.putObject("metadata").putObject("tags").put("title",path);
        return sharing.pack(doc);
    }
    @Test void wholeShareUploadStreamsPartsAndResumesAfterInvalidTail() throws Exception {
        basic();var recipient=sourceService.create(new com.mynora.singverse.media.dto.MediaSourceDtos.Input("Recipient","WEBDAV",address()+"recipient/","reader","test-password",profileId,false,null));
        byte[] first=oneSharedSong("One-Artist.mp3"),second=oneSharedSong("Two-Artist.mp3");
        assertThatThrownBy(()->shareJobs.importStream(recipient.id(),new java.io.ByteArrayInputStream(outerSet(first,second,true)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(indexedSongs.count(recipient.id())).isEqualTo(1);
        String csrf=body(request("/api/v1/auth/csrf","GET",null,false)).path("token").asText();
        var upload=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+SOURCES+"/"+recipient.id()+"/embedded-metadata/share")).header("Content-Type","application/zip").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofByteArray(outerSet(first,second,false))).build();
        var response=client.send(upload,HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);assertThat(body(response).path("imported").asInt()).isEqualTo(2);
        assertThat(indexedSongs.count(recipient.id())).isEqualTo(2);
        shareJobs.importStream(recipient.id(),new java.io.ByteArrayInputStream(outerSet(first,second,false)));assertThat(indexedSongs.count(recipient.id())).isEqualTo(2);
        assertThat(methods).isEmpty();
    }

    @Test void outerUploadRejectsOversizedExpandedPartBeforeImport() throws Exception {
        basic();var recipient=sourceService.create(new com.mynora.singverse.media.dto.MediaSourceDtos.Input("Recipient","WEBDAV",address()+"recipient/","reader","test-password",profileId,false,null));
        var out=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(out)){
            zip.putNextEntry(new java.util.zip.ZipEntry("index.json"));zip.write("{\"format\":\"singverse-share-set\",\"version\":2,\"parts\":[\"part-000001.zip\"]}".getBytes());zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("part-000001.zip"));byte[] block=new byte[65536];for(int i=0;i<513;i++)zip.write(block);zip.closeEntry();
        }
        assertThatThrownBy(()->shareJobs.importStream(recipient.id(),new java.io.ByteArrayInputStream(out.toByteArray()))).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(413));
        assertThat(indexedSongs.count(recipient.id())).isZero();
    }

    @Test void unavailableArtworkDoesNotBlockTextOrSharingAndLargeArtworkIsBounded() throws Exception {
        basic();listing=path->xml(entry(href(path),true)+entry("One-Artist.mp3",false));String source=source();await(start(source));
        String sid=source.substring(source.lastIndexOf('/')+1),id=db.queryForObject("SELECT id FROM media_song",String.class);
        var track=new com.mynora.singverse.media.metadata.ExternalTrack(com.mynora.singverse.media.metadata.MusicProvider.NETEASE,"one","Saved title",List.of("Saved artist"),"Saved album",1000,null,List.of(),"https://p1.music.126.net/fixture.jpg","UNKNOWN",null);
        appliedMapper.insertApplied(id,sid,com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(indexedSongs.get(id)),json.writeValueAsString(track),"[\"title\",\"album\",\"cover\"]","{\"title\":{\"kind\":\"PLATFORM\"},\"album\":{\"kind\":\"PLATFORM\"},\"cover\":{\"kind\":\"PLATFORM\",\"provider\":\"NETEASE\"}}","2026-10-31T00:00:00Z");
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"unavailable artwork")).when(fileCovers).applied(id);
        metadataFiles.runNext();assertThat(metadataFiles.pending(sid)).isZero();assertThat(metadataFiles.status(sid).get("attachments")).isEqualTo(1);
        assertThat(Files.readString(metadataFiles.directory(sid,id).resolve("media.nfo"))).contains("Saved title","Saved album");
        String job=shareJobs.create(sid,null).get("id").toString();shareJobs.runNext();assertThat(shareJobs.get(sid,job).get("state")).isEqualTo("SUCCEEDED");
        var image=new java.awt.image.BufferedImage(1536,1536,java.awt.image.BufferedImage.TYPE_INT_RGB);var random=new Random(11);for(int y=0;y<1536;y++)for(int x=0;x<1536;x++)image.setRGB(x,y,random.nextInt());
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"jpeg",bytes);assertThat(bytes.size()).isLessThan(2_000_000);
        org.mockito.Mockito.doReturn(new com.mynora.singverse.media.metadata.MetadataCoverService.Cover(bytes.toByteArray(),org.springframework.http.MediaType.IMAGE_JPEG)).when(fileCovers).applied(id);
        metadataFiles.retry(sid);metadataFiles.runNext();assertThat(metadataFiles.status(sid).get("attachments")).isEqualTo(0);
        assertThat(Files.size(metadataFiles.directory(sid,id).resolve("media.png"))).isLessThanOrEqualTo(2_000_000);
        assertThat(methods).allMatch("PROPFIND"::equals);
    }

}
