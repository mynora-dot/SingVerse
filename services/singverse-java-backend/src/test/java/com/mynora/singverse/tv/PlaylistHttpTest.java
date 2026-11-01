package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class PlaylistHttpTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("playlists.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TvMapper db;
    HttpClient admin, guest;

    @BeforeEach
    void clients() {
        admin =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        guest = HttpClient.newHttpClient();
    }

    HttpResponse<String> req(
            HttpClient client, String path, String method, Object body, String token, boolean csrf)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (csrf) {
            String value =
                    json.readTree(req(client, "/api/v1/auth/csrf", "GET", null, null, false).body())
                            .path("token")
                            .asText();
            b.header("X-CSRF-Token", value);
        }
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(
            HttpClient client, String path, String method, Object body, String token, boolean csrf)
            throws Exception {
        var r = req(client, path, method, body, token, csrf);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    String device() throws Exception {
        ok(
                admin,
                "/api/v1/auth/login",
                "POST",
                Map.of("username", "admin", "password", "admin"),
                null,
                true);
        var pending =
                ok(
                        guest,
                        "/api/v1/device-authorizations",
                        "POST",
                        Map.of("name", "客厅电视"),
                        null,
                        false);
        String id = pending.path("id").asText(), secret = pending.path("pollSecret").asText();
        assertThat(
                        req(
                                        guest,
                                        "/api/v1/device-authorizations/" + id + "/poll",
                                        "POST",
                                        Map.of("pollSecret", "wrong"),
                                        null,
                                        false)
                                .statusCode())
                .isEqualTo(401);
        ok(
                admin,
                "/api/v1/admin/device-authorizations/" + id + "/approve",
                "POST",
                Map.of("code", pending.path("code").asText()),
                null,
                true);
        var first =
                ok(
                        guest,
                        "/api/v1/device-authorizations/" + id + "/poll",
                        "POST",
                        Map.of("pollSecret", secret),
                        null,
                        false);
        var repeat =
                ok(
                        guest,
                        "/api/v1/device-authorizations/" + id + "/poll",
                        "POST",
                        Map.of("pollSecret", secret),
                        null,
                        false);
        assertThat(repeat.path("token")).isEqualTo(first.path("token"));
        return first.path("token").asText();
    }

    @Autowired com.mynora.singverse.media.repository.MediaSongRepository songRepository;
    @Autowired com.mynora.singverse.media.artist.ArtistMapper artists;
    @Autowired TvLibrary library;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixture;

    String song(String title, List<String> credits) throws Exception {
        String id = UUID.randomUUID().toString(),
                source = UUID.randomUUID().toString(),
                file = id + ".mp4";
        Files.writeString(temp.resolve(file), "0123456789");
        fixture.update(
                "INSERT INTO"
                    + " media_source(id,name,source_type,location,location_hash,username,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                source,
                "歌手库测试",
                "LOCAL",
                temp.toString(),
                source,
                "",
                "2026-10-29T00:00:00Z",
                "2026-10-29T00:00:00Z");
        var parsed =
                new com.mynora.singverse.media.filename.FilenameParseResult(
                        file,
                        file,
                        title,
                        credits,
                        com.mynora.singverse.media.filename.FilenameParseResult.Language.MANDARIN,
                        com.mynora.singverse.media.filename.FilenameParseResult.VersionType.UNKNOWN,
                        null,
                        "mp4",
                        List.of(),
                        1.0,
                        com.mynora.singverse.media.filename.FilenameParseResult.ParseStatus.SUCCESS,
                        com.mynora.singverse.media.filename.FilenameParseResult.RuleId.TITLE_ONLY,
                        List.of(),
                        false,
                        List.of());
        songRepository.replace(
                source,
                List.of(
                        new com.mynora.singverse.media.repository.MediaSongRepository.Stored(
                                id,
                                source,
                                file,
                                id,
                                10L,
                                "2026-10-29T00:00:00Z",
                                "2026-10-29T00:00:00Z",
                                "default",
                                0,
                                null,
                                songRepository.encode(parsed),
                                false)));
        library.invalidate();
        return id;
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.mynora.singverse.ai.AiModelClient models;

    Map<String, Object> playlist(String id, String name, List<String> songs, long revision) {
        return Map.of(
                "requestId",
                id,
                "name",
                name,
                "description",
                "家中聚会",
                "songIds",
                songs,
                "revision",
                revision);
    }

    JsonNode createPlaylist(String request, List<String> ids) throws Exception {
        var r =
                req(
                        admin,
                        "/api/v1/playlists",
                        "POST",
                        playlist(request, "聚会歌单", ids, 0),
                        null,
                        true);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(201);
        return json.readTree(r.body());
    }

    @Test
    void playlistsAreOrderedSharedVersionedAndDoNotChangeTheLibrary() throws Exception {
        String token = device(),
                one = song("歌单曲甲", List.of("歌手")),
                two = song("歌单曲乙", List.of("歌手")),
                id = UUID.randomUUID().toString();
        var raw = songRepository.get(one);
        var artwork =
                new com.mynora.singverse.media.metadata.ExternalTrack(
                        null,
                        null,
                        "歌单曲甲",
                        List.of("歌手"),
                        null,
                        null,
                        null,
                        List.of(),
                        "asset:playlist-cover",
                        "UNKNOWN",
                        null);
        fixture.update(
                "INSERT INTO"
                    + " song_metadata(song_id,source_id,fingerprint,track_json,fields_json,origins_json,applied_at)"
                    + " VALUES(?,?,?,?,?,?,?)",
                one,
                raw.sourceId(),
                com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(raw),
                json.writeValueAsString(artwork),
                "[\"cover\"]",
                "{}",
                "2026-10-30T00:00:00Z");
        library.invalidate();
        var first = createPlaylist(id, List.of(one, two));
        assertThat(first.path("coverPath").asText()).startsWith("/api/v1/metadata/songs/");
        assertThat(
                        ok(guest, "/api/v1/tv/library/playlists/" + id, "GET", null, token, false)
                                .path("coverPath")
                                .asText())
                .startsWith("/api/v1/tv/library/songs/");
        assertThat(first.path("count").asInt()).isEqualTo(2);
        assertThat(ok(admin, "/api/v1/playlists?sort=SONGS_DESC", "GET", null, null, false).path("totalElements").asInt()).isGreaterThan(0);
        assertThat(req(admin, "/api/v1/playlists?sort=INVALID", "GET", null, null, false).statusCode()).isEqualTo(400);
        createPlaylist(id, List.of(one, two));
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/playlists",
                                        "POST",
                                        playlist(id, "其他名字", List.of(one), 0),
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(409);
        var tv =
                ok(
                        guest,
                        "/api/v1/tv/library/playlists/" + id + "/songs?page=0&size=1",
                        "GET",
                        null,
                        token,
                        false);
        assertThat(tv.path("items").get(0).path("id").asText()).isEqualTo(one);
        assertThat(tv.toString()).doesNotContain("relativePath", "file_size", "sourceLocation");
        assertThat(req(guest, "/api/v1/playlists", "GET", null, token, false).statusCode())
                .isEqualTo(401);
        ok(
                admin,
                "/api/v1/playlists/" + id,
                "PUT",
                playlist("", "聚会歌单", List.of(two, one), 1),
                null,
                true);
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/playlists/" + id,
                                        "PUT",
                                        playlist("", "旧修改", List.of(one), 1),
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/playlists/" + id + "/songs",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("items")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(two);
        assertThat(req(admin, "/api/v1/playlists/" + id, "DELETE", null, null, false).statusCode())
                .isEqualTo(403);
        fixture.update("DELETE FROM media_song WHERE id=?", two);
        library.invalidate();
        var missing = ok(admin, "/api/v1/playlists/" + id, "GET", null, null, false);
        assertThat(missing.path("count").asInt()).isEqualTo(1);
        assertThat(missing.path("missingCount").asInt()).isEqualTo(1);
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/playlists/" + id + "?revision=1",
                                        "DELETE",
                                        null,
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/playlists/" + id + "?revision=2",
                                        "DELETE",
                                        null,
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(204);
        assertThat(songRepository.get(one).result().title()).isEqualTo("歌单曲甲");
        assertThat(
                        req(guest, "/api/v1/tv/library/playlists/" + id, "GET", null, token, false)
                                .statusCode())
                .isEqualTo(404);
    }

    @Test
    void aiDraftIsPreviewOnlyAndRejectsInventedIdsAndRateLimitErrors() throws Exception {
        device();
        String one = song("AI聚会歌", List.of("歌手"));
        var input =
                new com.mynora.singverse.ai.AiDtos.ServiceInput(
                        "歌单模型",
                        "custom",
                        "CHAT_COMPLETIONS",
                        "http://127.0.0.1:12345/v1/chat/completions",
                        "test-model",
                        "JSON_OBJECT",
                        null,
                        false,
                        true,
                        5,
                        5,
                        0);
        var response = req(admin, "/api/v1/ai/models", "POST", input, null, true);
        assertThat(response.statusCode()).isEqualTo(201);
        String model = json.readTree(response.body()).path("id").asText();
        org.mockito.Mockito.when(
                        models.generate(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        json.writeValueAsString(
                                Map.of(
                                        "name",
                                        "AI建议",
                                        "description",
                                        "从已有曲库选择",
                                        "songIds",
                                        List.of(one, one))));
        var request = Map.of("prompt", "朋友聚会", "modelServiceId", model, "q", "AI聚会歌", "limit", 5);
        var draft = ok(admin, "/api/v1/playlists/generate", "POST", request, null, true);
        assertThat(draft.path("songIds").size()).isEqualTo(1);
        assertThat(draft.path("songs").get(0).path("id").asText()).isEqualTo(one);
        String sent =
                org.mockito.Mockito.mockingDetails(models)
                        .getInvocations()
                        .iterator()
                        .next()
                        .getArguments()[3]
                        .toString();
        assertThat(sent).contains("AI聚会歌").doesNotContain("relativePath", "sourceLocation");
        assertThat(
                        ok(admin, "/api/v1/playlists?q=AI建议", "GET", null, null, false)
                                .path("totalElements")
                                .asInt())
                .isZero();
        org.mockito.Mockito.when(
                        models.generate(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        "{\"name\":\"幻觉\",\"description\":\"错误\",\"songIds\":[\"not-in-library\"]}");
        assertThat(
                        req(admin, "/api/v1/playlists/generate", "POST", request, null, true)
                                .statusCode())
                .isEqualTo(502);
        org.mockito.Mockito.when(
                        models.generate(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.mynora.singverse.ai.AiTransport.Failure("限流", 429));
        assertThat(
                        req(admin, "/api/v1/playlists/generate", "POST", request, null, true)
                                .statusCode())
                .isEqualTo(429);
    }
}
