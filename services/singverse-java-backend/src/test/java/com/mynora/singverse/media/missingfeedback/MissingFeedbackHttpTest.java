package com.mynora.singverse.media.missingfeedback;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.filename.FilenameParseResult;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.tv.TvLibrary;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class MissingFeedbackHttpTest {
    private static final String BASE = "/api/v1/missing-song-feedback";
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("feedback.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixture;
    @Autowired MediaSongRepository songs;
    @Autowired TvLibrary library;
    HttpClient admin, guest;

    @BeforeEach void prepare() {
        fixture.update("DELETE FROM missing_song_feedback");
        admin = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        guest = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    HttpResponse<String> request(HttpClient client, String path, String method, Object body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf) request.header("X-CSRF-Token", json.readTree(request(client, "/api/v1/auth/csrf", "GET", null, false).body()).path("token").asText());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode response(String path, String method, Object body, int status) throws Exception {
        var response = request(admin, path, method, body, !method.equals("GET"));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
    void login() throws Exception { response("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), 200); }
    Map<String, Object> create(String id, String title, String artist) {
        return Map.of("requestId", id, "title", title, "artist", artist, "versionLabel", "原版MV", "description", "希望补齐\n朋友想唱", "submittedBy", "小林");
    }
    Map<String, Object> update(long revision, String status, String note, String song) {
        var result = new HashMap<String, Object>();
        result.put("revision", revision); result.put("status", status); result.put("handlingNote", note); result.put("resolvedSongId", song);
        return result;
    }
    String song() throws Exception {
        String id = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), filename = id + ".mp4";
        Files.writeString(temp.resolve(filename), "isolated media fixture");
        fixture.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                source, "缺歌补齐测试", "LOCAL", temp.toString(), source, "", "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z");
        var parsed = new FilenameParseResult(filename, filename, "后来", List.of("刘若英"), FilenameParseResult.Language.MANDARIN,
                FilenameParseResult.VersionType.UNKNOWN, null, "mp4", List.of(), 1.0, FilenameParseResult.ParseStatus.SUCCESS,
                FilenameParseResult.RuleId.TITLE_ONLY, List.of(), false, List.of());
        songs.replace(source, List.of(new MediaSongRepository.Stored(id, source, filename, id, 22L, "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z", "default", 0, null, songs.encode(parsed), false)));
        library.invalidate();
        return id;
    }

    @Test void requiresAdministratorSessionAndCsrf() throws Exception {
        assertThat(request(guest, BASE, "GET", null, false).statusCode()).isEqualTo(401);
        assertThat(request(guest, BASE, "POST", create(UUID.randomUUID().toString(), "后来", "刘若英"), true).statusCode()).isEqualTo(401);
        login();
        assertThat(request(admin, BASE, "POST", create(UUID.randomUUID().toString(), "后来", "刘若英"), false).statusCode()).isEqualTo(403);
        var page = response(BASE, "GET", null, 200);
        assertThat(page.path("totalElements").asInt()).isZero();
        assertThat(page.path("items").isEmpty()).isTrue();
    }

    @Test void registersIdempotentlyAndKeepsImmutableOriginalFeedbackAcrossHandlingAndReopen() throws Exception {
        login();
        String id = UUID.randomUUID().toString(), path = BASE + "/" + id;
        var input = create(id, "后来", "刘若英");
        var created = response(BASE, "POST", input, 201);
        assertThat(created.path("status").asText()).isEqualTo("PENDING");
        assertThat(created.path("source").asText()).isEqualTo("ADMIN");
        assertThat(created.path("revision").asInt()).isEqualTo(1);
        assertThat(created.path("createdAt").asText()).endsWith("Z");
        assertThat(response(BASE, "POST", input, 201)).isEqualTo(created);
        response(BASE, "POST", create(id, "其他歌曲", "刘若英"), 409);
        var processing = response(path, "PUT", update(1, "PROCESSING", "正在整理用户自备文件", null), 200);
        assertThat(processing.path("revision").asInt()).isEqualTo(2);
        response(path, "PUT", update(1, "CLOSED", "过期请求", null), 409);
        response(path, "PUT", update(2, "RESOLVED", "", null), 400);
        response(path, "PUT", update(2, "RESOLVED", "", "missing-song"), 409);
        String songId = song();
        var resolved = response(path, "PUT", update(2, "RESOLVED", "原版 MV 已加入曲库", songId), 200);
        assertThat(resolved.path("resolvedSongAvailable").asBoolean()).isTrue();
        assertThat(resolved.path("resolvedSongTitle").asText()).isEqualTo("后来");
        assertThat(resolved.path("resolvedSongArtists").get(0).asText()).isEqualTo("刘若英");
        assertThat(resolved.path("resolvedAt").asText()).endsWith("Z");
        assertThat(resolved.path("description")).isEqualTo(created.path("description"));
        assertThat(response(BASE, "POST", input, 201)).isEqualTo(resolved);
        fixture.update("DELETE FROM media_song WHERE id=?", songId);
        library.invalidate();
        var removed = response(path, "GET", null, 200);
        assertThat(removed.path("resolvedSongAvailable").asBoolean()).isFalse();
        assertThat(removed.path("resolvedSongTitle").asText()).isEqualTo("后来");
        var reopened = response(path, "PUT", update(3, "PENDING", "想换成现场版", null), 200);
        assertThat(reopened.path("resolvedSongId").isNull()).isTrue();
        assertThat(reopened.path("resolvedAt").isNull()).isTrue();
        assertThat(reopened.path("resolvedSongArtists").isEmpty()).isTrue();
        assertThat(reopened.path("createdAt")).isEqualTo(created.path("createdAt"));
        var closed = response(path, "PUT", update(4, "CLOSED", "暂不补齐", null), 200);
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(response(BASE, "GET", null, 200).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test void filtersBeforePaginationAndTreatsSearchWildcardsAsLiteralText() throws Exception {
        login();
        for (int i = 0; i < 24; i++) {
            String id = UUID.randomUUID().toString();
            response(BASE, "POST", create(id, i == 0 ? "100%_特别版" : "后来" + i, i % 2 == 0 ? "刘若英" : "其他歌手"), 201);
            fixture.update("UPDATE missing_song_feedback SET created_at=? WHERE id=?", "2026-11-06T00:00:" + String.format("%02d", i) + ".000Z", id);
            if (i % 2 == 0) response(BASE + "/" + id, "PUT", update(1, "PROCESSING", "待整理", null), 200);
        }
        var first = response(BASE + "?status=PROCESSING&size=5&page=0", "GET", null, 200);
        var next = response(BASE + "?status=PROCESSING&size=5&page=1", "GET", null, 200);
        assertThat(first.path("totalElements").asInt()).isEqualTo(12);
        assertThat(first.path("items").size()).isEqualTo(5);
        assertThat(first.path("items").get(0).path("title").asText()).isEqualTo("后来22");
        assertThat(next.path("items").get(0).path("title").asText()).isEqualTo("后来12");
        assertThat(response(BASE + "?q=" + URLEncoder.encode("刘若英", java.nio.charset.StandardCharsets.UTF_8), "GET", null, 200).path("totalElements").asInt()).isEqualTo(12);
        assertThat(response(BASE + "?q=%25_", "GET", null, 200).path("totalElements").asInt()).isEqualTo(1);
        assertThat(response(BASE + "?q=liuruoying", "GET", null, 200).path("totalElements").asInt()).isEqualTo(12);
        assertThat(response(BASE + "?page=999", "GET", null, 200).path("items").isEmpty()).isTrue();
    }

    @Test void rejectsInvalidInputWithoutCreatingOrUpdatingFeedback() throws Exception {
        login();
        for (String suffix : List.of("?status=UNKNOWN", "?page=-1", "?size=101", "?q=%0A")) response(BASE + suffix, "GET", null, 400);
        for (String blank : List.of(" ", "\u3000"))
            response(BASE, "POST", create(UUID.randomUUID().toString(), blank, "歌手"), 400);
        response(BASE, "POST", create("bad-request", "歌曲", "歌手"), 400);
        response(BASE, "POST", create(UUID.randomUUID().toString(), "歌曲\n名称", "歌手"), 400);
        response(BASE, "POST", create(UUID.randomUUID().toString(), "歌".repeat(201), "歌手"), 400);
        String id = UUID.randomUUID().toString();
        response(BASE, "POST", create(id, "歌曲", "歌手"), 201);
        response(BASE + "/" + id, "PUT", update(1, "UNKNOWN", "", null), 400);
        response(BASE + "/" + id, "PUT", update(1, "PROCESSING", "", "some-song"), 400);
        response(BASE + "/" + id, "PUT", Map.of("status", "CLOSED", "handlingNote", ""), 400);
        response(BASE + "/unknown", "GET", null, 404);
        assertThat(response(BASE + "/" + id, "GET", null, 200).path("revision").asInt()).isEqualTo(1);
        assertThat(response(BASE, "GET", null, 200).path("totalElements").asInt()).isEqualTo(1);
    }
}
