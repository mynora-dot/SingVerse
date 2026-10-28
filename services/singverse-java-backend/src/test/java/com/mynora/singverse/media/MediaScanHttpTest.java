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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
class MediaScanHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("scan.db").toString());
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

    HttpResponse<String> video(String id, String range, String method) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/media-songs/" + id + "/video"));
        if (range != null) builder.header("Range", range);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    String playbackSong(Path directory) throws Exception {
        createProfile();
        Files.writeString(directory.resolve("晴天-周杰伦.mp4"), "0123456789");
        String source = local(directory);
        assertThat(request(source + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        return body(request(source + "/songs", "GET", null, false)).at("/items/0/id").asText();
    }
    @Test void localVideoRequiresSessionSupportsRangesAndRejectsEscapedFiles() throws Exception {
        assertThat(video("missing", null, "GET").statusCode()).isEqualTo(401);
        login();
        Path directory = Files.createTempDirectory(temp, "video-");
        String id = playbackSong(directory);
        var full = video(id, null, "GET");
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo("0123456789");
        assertThat(full.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("video/mp4");
        assertThat(full.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var partial = video(id, "bytes=2-5", "GET");
        assertThat(partial.statusCode()).isEqualTo(206);
        assertThat(partial.body()).isEqualTo("2345");
        assertThat(partial.headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes 2-5/10");
        assertThat(video(id, "bytes=-3", "GET").body()).isEqualTo("789");
        assertThat(video(id, "bytes=8-", "GET").body()).isEqualTo("89");
        var invalid = video(id, "bytes=20-", "GET");
        assertThat(invalid.statusCode()).isEqualTo(416);
        assertThat(invalid.headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes */10");
        assertThat(video(id, "bytes=1-2,4-5", "GET").statusCode()).isEqualTo(400);
        var head = video(id, null, "HEAD");
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Length").orElseThrow()).isEqualTo("10");
        Path media = directory.resolve("晴天-周杰伦.mp4");
        Files.delete(media);
        Files.createSymbolicLink(media, Files.writeString(temp.resolve("outside-video.mp4"), "private"));
        assertThat(video(id, null, "GET").statusCode()).isEqualTo(404);
        db.update("UPDATE media_song SET relative_path=? WHERE id=?", "../outside-video.mp4", id);
        assertThat(video(id, null, "GET").statusCode()).isEqualTo(400);
        assertThat(video("missing", null, "GET").statusCode()).isEqualTo(404);
    }

    @Test void localAudioScanIncludesCommonFormatsAndStreamsAudioMimeAndRanges() throws Exception {
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "audio-");
        var formats = Map.ofEntries(Map.entry("mp3", "audio/mpeg"), Map.entry("flac", "audio/flac"),
                Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"), Map.entry("aac", "audio/aac"),
                Map.entry("ogg", "audio/ogg"), Map.entry("oga", "audio/ogg"), Map.entry("opus", "audio/ogg"),
                Map.entry("wma", "audio/x-ms-wma"), Map.entry("aif", "audio/aiff"), Map.entry("aiff", "audio/aiff"), Map.entry("ape", "audio/x-ape"));
        for (String extension : formats.keySet()) Files.writeString(directory.resolve("晴天-周杰伦." + extension.toUpperCase(Locale.ROOT)), "0123456789");
        Files.writeString(directory.resolve("notes.txt"), "not media");
        String source = local(directory);
        var scan = body(request(source + "/scan", "POST", null, true));
        assertThat(scan.path("totalSongs").asInt()).isEqualTo(formats.size());
        var page = body(request(source + "/songs?size=100", "GET", null, false));
        for (var song : page.path("items")) {
            assertThat(song.at("/result/title").asText()).isEqualTo("晴天");
            assertThat(song.at("/result/needsReview").asBoolean()).isFalse();
            String id = song.path("id").asText();
            var part = video(id, "bytes=2-5", "GET");
            assertThat(part.statusCode()).isEqualTo(206);
            assertThat(part.body()).isEqualTo("2345");
            assertThat(part.headers().firstValue("Content-Type").orElseThrow()).isEqualTo(formats.get(song.at("/result/extension").asText()));
            assertThat(video(id, null, "HEAD").headers().firstValue("Content-Length").orElseThrow()).isEqualTo("10");
        }
    }

    @Test void webdavVideoUsesServerCredentialsPreservesRangeAndNeverFollowsRedirects() throws Exception {
        login();
        String id = playbackSong(Files.createTempDirectory(temp, "remote-video-"));
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new java.util.concurrent.atomic.AtomicInteger(0);
        var seenPath = new java.util.concurrent.atomic.AtomicReference<String>();
        var seenAuth = new java.util.concurrent.atomic.AtomicReference<String>();
        var seenRange = new java.util.concurrent.atomic.AtomicReference<String>();
        var followed = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/dav/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenRange.set(exchange.getRequestHeaders().getFirst("Range"));
            if (mode.get() == 1) {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/download");
                exchange.sendResponseHeaders(302, -1);
            } else if (mode.get() == 2) { exchange.sendResponseHeaders(401, -1); }
            else if (mode.get() == 3) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */10"); exchange.sendResponseHeaders(416, -1);
            } else if (mode.get() == 4) {
                exchange.sendResponseHeaders(206, 4); exchange.getResponseBody().write("2345".getBytes());
            } else {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                if (seenRange.get() != null) exchange.getResponseHeaders().set("Content-Range", "bytes 2-5/10");
                byte[] bytes = (seenRange.get() == null ? "0123456789" : "2345").getBytes();
                exchange.sendResponseHeaders(seenRange.get() == null ? 200 : 206, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/download", exchange -> { followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var source = request(SOURCES, "POST", Map.of("name", "播放远端", "type", "WEBDAV", "location", "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/", "username", "reader", "password", "test-password", "parserProfileId", profileId), true);
            assertThat(source.statusCode()).isEqualTo(201);
            db.update("UPDATE media_song SET source_id=?, relative_path=? WHERE id=?", body(source).path("id").asText(), "子目录/歌手 - 歌名 #1%.mp4", id);
            assertThat(video(id, null, "GET").body()).isEqualTo("0123456789");
            var partial = video(id, "bytes=2-5", "GET");
            assertThat(partial.statusCode()).isEqualTo(206);
            assertThat(partial.body()).isEqualTo("2345");
            assertThat(partial.headers().firstValue("Content-Range").orElseThrow()).isEqualTo("bytes 2-5/10");
            assertThat(seenRange.get()).isEqualTo("bytes=2-5");
            assertThat(seenPath.get()).isEqualTo("/dav/子目录/歌手 - 歌名 #1%.mp4");
            assertThat(seenAuth.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("reader:test-password".getBytes()));
            for (var audio : Map.of("MP3", "audio/mpeg", "FLAC", "audio/flac", "WAV", "audio/wav", "M4A", "audio/mp4", "OPUS", "audio/ogg").entrySet()) {
                db.update("UPDATE media_song SET relative_path=? WHERE id=?", "子目录/音频." + audio.getKey(), id);
                var streamed = video(id, "bytes=2-5", "GET");
                assertThat(streamed.statusCode()).isEqualTo(206);
                assertThat(streamed.body()).isEqualTo("2345");
                assertThat(streamed.headers().firstValue("Content-Type").orElseThrow()).isEqualTo(audio.getValue());
            }
            mode.set(1);
            var redirect = video(id, null, "GET");
            assertThat(redirect.statusCode()).isEqualTo(502);
            assertThat(redirect.body()).doesNotContain("test-password", "/download");
            assertThat(followed.get()).isZero();
            mode.set(2); assertThat(video(id, null, "GET").statusCode()).isEqualTo(502);
            mode.set(3); assertThat(video(id, "bytes=20-", "GET").statusCode()).isEqualTo(416);
            mode.set(4); assertThat(video(id, "bytes=2-5", "GET").statusCode()).isEqualTo(502);
        } finally { server.stop(0); }
    }

    @Test void directoryBrowserRequiresLoginAndListsOnlyDirectories() throws Exception {
        String endpoint = SOURCES + "/local-directories";
        assertThat(request(endpoint, "GET", null, false).statusCode()).isEqualTo(401);
        login();
        Path root = Files.createTempDirectory(temp, "browse-");
        Files.createDirectory(root.resolve("歌曲"));
        Files.writeString(root.resolve("private.txt"), "never returned");
        Files.createSymbolicLink(root.resolve("link"), root.resolve("歌曲"));
        String query = "?path=" + URLEncoder.encode(root.toString(), java.nio.charset.StandardCharsets.UTF_8);
        var response = request(endpoint + query, "GET", null, false);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = body(response);
        assertThat(result.path("path").asText()).isEqualTo(root.toRealPath().toString());
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.at("/items/0/name").asText()).isEqualTo("歌曲");
        assertThat(result.path("parent").asText()).isEqualTo(root.getParent().toRealPath().toString());
        assertThat(request(endpoint + "?path=relative", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request(endpoint + query + "&page=-1", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request(endpoint + "?path=" + URLEncoder.encode(root.resolve("private.txt").toString(), java.nio.charset.StandardCharsets.UTF_8), "GET", null, false).statusCode()).isEqualTo(400);
        for (int i = 0; i < 101; i++) Files.createDirectory(root.resolve(String.format("folder-%03d", i)));
        assertThat(body(request(endpoint + query, "GET", null, false)).path("hasMore").asBoolean()).isTrue();
        var second = body(request(endpoint + query + "&page=1", "GET", null, false));
        assertThat(second.path("items").size()).isEqualTo(2);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
    }

    @Test void localScanPersistsSelectedProfileResultsAndRescanReplacesIndexWithoutTouchingOriginals() throws Exception {
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "songs-");
        Path nested = Files.createDirectories(directory.resolve("sub"));
        Path first = Files.writeString(directory.resolve("晴天-周杰伦.mkv"), "original");
        Files.writeString(nested.resolve("夜曲-周杰伦.MP4"), "original");
        Files.writeString(directory.resolve("没有分隔符.mkv"), "original");
        Files.writeString(directory.resolve("notes.txt"), "not-media");
        Path outside = Files.createTempDirectory(temp, "outside-");
        Files.writeString(outside.resolve("外部-歌手.mkv"), "outside");
        Files.createSymbolicLink(directory.resolve("linked"), outside);
        String path = local(directory);
        var summary = request(path + "/scan", "POST", null, true);
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(body(summary).path("totalSongs").asInt()).isEqualTo(3);
        assertThat(body(summary).path("matchedSongs").asInt()).isEqualTo(2);
        assertThat(body(summary).path("needsReviewSongs").asInt()).isEqualTo(1);
        assertThat(body(summary).path("parserProfileId").asText()).isEqualTo(profileId);
        var page = body(request(path + "/songs?size=100", "GET", null, false));
        var rows = new ArrayList<JsonNode>(); page.path("items").forEach(rows::add);
        JsonNode sunny = rows.stream().filter(r -> r.path("relativePath").asText().equals("晴天-周杰伦.mkv")).findFirst().orElseThrow();
        assertThat(sunny.at("/result/title").asText()).isEqualTo("晴天");
        assertThat(sunny.at("/result/artists/0").asText()).isEqualTo("周杰伦");
        assertThat(sunny.path("selectedRuleId").isNull()).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song", Integer.class)).isEqualTo(3);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        var repeated = body(request(path + "/songs?size=100", "GET", null, false));
        assertThat(repeated).isEqualTo(body(request(path + "/songs?size=100", "GET", null, false)));
        assertThat(repeated.path("items").toString()).contains(sunny.path("id").asText());
        Files.delete(nested.resolve("夜曲-周杰伦.MP4"));
        assertThat(body(request(path + "/scan", "POST", null, true)).path("totalSongs").asInt()).isEqualTo(2);
        assertThat(Files.readString(first)).isEqualTo("original");
        assertThat(Files.readString(outside.resolve("外部-歌手.mkv"))).isEqualTo("outside");
        assertThat(request(path, "DELETE", null, true).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM media_song", Integer.class)).isZero();
        assertThat(Files.exists(first)).isTrue();
    }

    @Test void profileUpdatesApplyOnlyOnRescanAndDisabledRulesDoNotFallback() throws Exception {
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "profile-");
        Files.writeString(directory.resolve("晴天-周杰伦.mkv"), "original");
        String path = local(directory);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        var workspace = workspace();
        String ruleId = selectedRuleId;
        assertThat(request(PARSER + "/rule-definitions/" + ruleId, "PUT", Map.of("revision", workspace.path("revision").asLong(),
                "definition", Map.of("name", "改为正向", "type", "TEMPLATE", "expression", "{artist}-{title}")), true).statusCode()).isEqualTo(200);
        assertThat(body(request(path + "/songs", "GET", null, false)).at("/items/0/result/title").asText()).isEqualTo("晴天");
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(body(request(path + "/songs", "GET", null, false)).at("/items/0/result/title").asText()).isEqualTo("周杰伦");
        assertThat(request(PARSER + "/profiles/" + profileId, "PUT", Map.of("revision", workspace().path("revision").asLong(),
                "profile", Map.of("name", "停用方案", "entries", List.of(Map.of("ruleId", ruleId, "enabled", false)))), true).statusCode()).isEqualTo(200);
        assertThat(body(request(path + "/scan", "POST", null, true)).path("matchedSongs").asInt()).isZero();
        assertThat(body(request(path + "/songs", "GET", null, false)).at("/items/0/result/needsReview").asBoolean()).isTrue();
    }

    @Test void invalidBindingsAndWebdavScanningAreRejectedAndReferencedProfilesCannotBeDeleted() throws Exception {
        login(); createProfile();
        var payload = new HashMap<String, Object>(Map.of("name", "远程来源", "type", "WEBDAV", "location", "https://nas.example.invalid/dav/", "parserProfileId", "missing"));
        assertThat(request(SOURCES, "POST", payload, true).statusCode()).isEqualTo(400);
        payload.put("parserProfileId", profileId);
        var created = body(request(SOURCES, "POST", payload, true));
        String path = SOURCES + "/" + created.path("id").asText();
        assertThat(created.path("parserProfileId").asText()).isEqualTo(profileId);
        payload.remove("parserProfileId");
        assertThat(body(request(path, "PUT", payload, true)).path("parserProfileId").asText()).isEqualTo(profileId);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(400);
        var blocked = request(PARSER + "/profiles/" + profileId + "?revision=" + workspace().path("revision").asLong(), "DELETE", null, true);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.body()).contains("远程来源");
        assertThat(request(path, "DELETE", null, true).statusCode()).isEqualTo(204);
        assertThat(request(PARSER + "/profiles/" + profileId + "?revision=" + workspace().path("revision").asLong(), "DELETE", null, true).statusCode()).isEqualTo(200);
        payload.put("location", "https://nas.example.invalid/old/");
        assertThat(body(request(SOURCES, "POST", payload, true)).path("parserProfileId").asText()).isEqualTo("default");
    }

    @Test void failedScanPreservesOldIndexAndEndpointsRequireAuthenticationAndCsrf() throws Exception {
        assertThat(request(SOURCES + "/missing/songs", "GET", null, false).statusCode()).isEqualTo(401);
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "offline-");
        Files.writeString(directory.resolve("晴天-周杰伦.mkv"), "original");
        String path = local(directory);
        assertThat(request(path + "/scan", "POST", null, false).statusCode()).isEqualTo(403);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        String before = request(path + "/songs", "GET", null, false).body();
        Path offline = directory.resolveSibling(directory.getFileName() + "-offline");
        Files.move(directory, offline);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(400);
        assertThat(request(path + "/songs", "GET", null, false).body()).isEqualTo(before);
        Files.move(offline, directory);
        Files.delete(directory.resolve("晴天-周杰伦.mkv"));
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(409);
        assertThat(request(path + "/songs", "GET", null, false).body()).isEqualTo(before);
        assertThat(request(path + "/songs?page=-1", "GET", null, false).statusCode()).isEqualTo(400);
    }

    @Test void orderedEnabledMembersAreUsedAndScanLimitKeepsPriorData() throws Exception {
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "ordered-");
        Files.writeString(directory.resolve("晴天-周杰伦.mkv"), "original");
        String path = local(directory);
        var forward = body(request(PARSER + "/rule-definitions", "POST", Map.of("revision", workspace().path("revision").asLong(),
                "definition", Map.of("name", "正向", "type", "TEMPLATE", "expression", "{artist}-{title}")), true));
        String forwardId = forward.path("changedId").asText();
        var entries = List.of(Map.of("ruleId", forwardId, "enabled", false), Map.of("ruleId", selectedRuleId, "enabled", true));
        assertThat(request(PARSER + "/profiles/" + profileId, "PUT", Map.of("revision", workspace().path("revision").asLong(),
                "profile", Map.of("name", "顺序方案", "entries", entries)), true).statusCode()).isEqualTo(200);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(body(request(path + "/songs", "GET", null, false)).at("/items/0/result/title").asText()).isEqualTo("晴天");
        entries = List.of(Map.of("ruleId", forwardId, "enabled", true), Map.of("ruleId", selectedRuleId, "enabled", true));
        assertThat(request(PARSER + "/profiles/" + profileId, "PUT", Map.of("revision", workspace().path("revision").asLong(),
                "profile", Map.of("name", "顺序方案", "entries", entries)), true).statusCode()).isEqualTo(200);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(body(request(path + "/songs", "GET", null, false)).at("/items/0/result/title").asText()).isEqualTo("周杰伦");
        String before = request(path + "/songs", "GET", null, false).body();
        for (int i = 0; i < 2000; i++) Files.createFile(directory.resolve("歌曲" + i + "-歌手.mkv"));
        var limited = request(path + "/scan", "POST", null, true);
        assertThat(limited.statusCode()).isEqualTo(400);
        assertThat(limited.body()).contains("2000");
        assertThat(request(path + "/songs", "GET", null, false).body()).isEqualTo(before);
    }


    @Test void databaseFailureRollsBackIndexReplacementAndSourceTimestamp() throws Exception {
        login(); createProfile();
        Path directory = Files.createTempDirectory(temp, "rollback-");
        Files.writeString(directory.resolve("晴天-周杰伦.mkv"), "original");
        String path = local(directory);
        assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        String before = request(path + "/songs", "GET", null, false).body();
        String timestamp = body(request(path, "GET", null, false)).path("updatedAt").asText();
        // A test-only trigger fails after the old index has been deleted inside the transaction.
        boolean sqlite;
        try (var connection = db.getDataSource().getConnection()) {
            sqlite = connection.getMetaData().getDatabaseProductName().equals("SQLite");
        }
        db.execute(sqlite
                ? "CREATE TRIGGER fail_song_insert BEFORE INSERT ON media_song BEGIN SELECT RAISE(ABORT, 'test insert failure'); END"
                : "CREATE TRIGGER fail_song_insert BEFORE INSERT ON media_song FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test insert failure'");
        try {
            assertThat(request(path + "/scan", "POST", null, true).statusCode()).isEqualTo(500);
            assertThat(request(path + "/songs", "GET", null, false).body()).isEqualTo(before);
            assertThat(body(request(path, "GET", null, false)).path("updatedAt").asText()).isEqualTo(timestamp);
        } finally { db.execute("DROP TRIGGER fail_song_insert"); }
    }


    @Test void songManagementCombinesScannedSourcesAndFiltersWithSafeLiteralSearch() throws Exception {
        assertThat(request("/api/v1/media-songs", "GET", null, false).statusCode()).isEqualTo(401);
        login(); createProfile();
        Path one = Files.createTempDirectory(temp, "list-one-");
        Files.writeString(one.resolve("晴天-周杰伦.mkv"), "original");
        Files.writeString(one.resolve("100%_歌曲-歌手.mkv"), "original");
        Files.writeString(one.resolve("未匹配.mkv"), "original");
        String first = local(one);
        Path two = Files.createTempDirectory(temp, "list-two-");
        Files.writeString(two.resolve("夜曲-周杰伦.mkv"), "original");
        String second = local(two);
        assertThat(request(first + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(request(second + "/scan", "POST", null, true).statusCode()).isEqualTo(200);
        var all = body(request("/api/v1/media-songs?size=100", "GET", null, false));
        assertThat(all.path("totalElements").asInt()).isEqualTo(4);
        assertThat(all.at("/items/0/sourceName").asText()).isEqualTo("本地歌曲");
        assertThat(body(request("/api/v1/media-songs?status=REVIEW", "GET", null, false)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(body(request("/api/v1/media-songs?status=MATCHED", "GET", null, false)).path("totalElements").asInt()).isEqualTo(3);
        assertThat(body(request("/api/v1/media-songs?q=%25", "GET", null, false)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(body(request("/api/v1/media-songs?q=_", "GET", null, false)).path("totalElements").asInt()).isEqualTo(1);
        String id = second.substring(second.lastIndexOf('/') + 1);
        var scoped = body(request("/api/v1/media-songs?sourceId=" + id, "GET", null, false));
        assertThat(scoped.path("totalElements").asInt()).isEqualTo(1);
        assertThat(scoped.at("/items/0/result/title").asText()).isEqualTo("夜曲");
        var page0 = body(request("/api/v1/media-songs?size=1", "GET", null, false));
        var page1 = body(request("/api/v1/media-songs?size=1&page=1", "GET", null, false));
        assertThat(page0.path("items").size()).isEqualTo(1);
        assertThat(page1.at("/items/0/id").asText()).isNotEqualTo(page0.at("/items/0/id").asText());
        assertThat(request("/api/v1/media-songs?status=invalid", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request("/api/v1/media-songs?size=101", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request(first, "DELETE", null, true).statusCode()).isEqualTo(204);
        assertThat(body(request("/api/v1/media-songs", "GET", null, false)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(Files.exists(one.resolve("晴天-周杰伦.mkv"))).isTrue();
    }

    @Test void sourceSearchFiltersBeforePaginationAndEscapesWildcards() throws Exception {
        login();
        var dir = Files.createTempDirectory(temp, "source-search");
        for (String name : List.of("家庭100%", "家庭普通"))
            assertThat(request(SOURCES, "POST", Map.of("name", name, "type", "LOCAL", "location", Files.createDirectory(dir.resolve(name)).toString()), true).statusCode()).isEqualTo(201);
        var match = body(request(SOURCES + "?q=%25&type=LOCAL&profileId=default&size=1", "GET", null, false));
        assertThat(match.path("totalElements").asInt()).isEqualTo(1);
        assertThat(match.at("/items/0/name").asText()).isEqualTo("家庭100%");
        assertThat(body(request(SOURCES + "?type=WEBDAV", "GET", null, false)).path("totalElements").asInt()).isZero();
        assertThat(body(request(SOURCES + "?type=LOCAL&size=1&page=1", "GET", null, false)).path("items").size()).isEqualTo(1);
        assertThat(request(SOURCES + "?type=invalid", "GET", null, false).statusCode()).isEqualTo(400);
    }
    @Test void deepPagingIdsUseCoveringFilterOrderIndexes() {
        String songPlan = db.queryForList("EXPLAIN QUERY PLAN SELECT id, scanned_at FROM media_song WHERE source_id=? AND needs_review=? ORDER BY scanned_at DESC,id ASC LIMIT 20 OFFSET 90000", "source", 1).toString();
        assertThat(songPlan).contains("COVERING INDEX idx_song_source_review_page").doesNotContain("TEMP B-TREE");
        String sourcePlan = db.queryForList("EXPLAIN QUERY PLAN SELECT id, created_at FROM media_source WHERE source_type=? ORDER BY created_at DESC,id ASC LIMIT 20 OFFSET 10000", "LOCAL").toString();
        assertThat(sourcePlan).contains("COVERING INDEX idx_source_type_page").doesNotContain("TEMP B-TREE");
    }

    @Test void sourceScheduleValidatesPersistsDisablesAndPreservesLegacyUpdates() throws Exception {
        login(); var directory = Files.createTempDirectory(temp, "schedule-");
        var input = new HashMap<String,Object>(Map.of("name", "定时歌曲", "type", "LOCAL", "location", directory.toString(), "scanEnabled", true, "scanCron", "0 3 * * *"));
        var response = request(SOURCES, "POST", input, true);
        assertThat(response.statusCode()).isEqualTo(201);
        var source = body(response); String path = SOURCES + "/" + source.path("id").asText();
        assertThat(source.path("scanEnabled").asBoolean()).isTrue();
        assertThat(source.path("nextScanAt").isNull()).isFalse();
        input.remove("scanEnabled"); input.remove("scanCron");
        var preserved = body(request(path, "PUT", input, true));
        assertThat(preserved.path("nextScanAt")).isEqualTo(source.path("nextScanAt"));
        input.put("scanCron", "0 0 3 * * *");
        assertThat(request(path, "PUT", input, true).statusCode()).isEqualTo(400);
        assertThat(body(request(path, "GET", null, false)).path("scanCron").asText()).isEqualTo("0 3 * * *");
        input.put("scanCron", "0 3 * * *"); input.put("scanEnabled", false);
        var disabled = body(request(path, "PUT", input, true));
        assertThat(disabled.path("scanEnabled").asBoolean()).isFalse();
        assertThat(disabled.path("nextScanAt").isNull()).isTrue();
    }

}
