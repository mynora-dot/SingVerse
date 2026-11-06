package com.mynora.singverse.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class SongActivityHttpTest {
    @TempDir static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("singverse.sqlite.path", () -> root.resolve("activity.db").toString());
        properties.add("singverse.auth.key-file", () -> root.resolve("auth.key").toString());
        properties.add("singverse.classification.worker-enabled", () -> false);
        properties.add("singverse.transcoding.cache-directory", () -> root.resolve("cache").toString());
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    HttpResponse<String> request(HttpClient client, String path, String method, Object body, boolean csrf)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf) {
            var token = request(client, "/api/v1/auth/csrf", "GET", null, false);
            assertThat(token.statusCode()).isEqualTo(200);
            request.header("X-CSRF-Token", json.readTree(token.body()).path("token").asText());
        }
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode expect(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    @Test
    void administratorQueriesAndFeedbackLifecycleRequireSessionAndCsrf() throws Exception {
        var guest = HttpClient.newHttpClient();
        for (String path : new String[] {"/api/v1/playback-history", "/api/v1/playback-history/popular",
                "/api/v1/playback-history/summary", "/api/v1/missing-song-feedback"}) {
            expect(request(guest, path, "GET", null, false), 401);
        }
        var admin = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        expect(request(admin, "/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true), 200);
        String base = "/api/v1/missing-song-feedback";
        var input = Map.of("requestId", UUID.randomUUID().toString(), "title", "想唱的新歌", "artist", "歌手",
                "versionLabel", "现场版", "description", "希望补充这个版本", "submittedBy", "朋友");
        expect(request(admin, base, "POST", input, false), 403);
        var created = expect(request(admin, base, "POST", input, true), 201);
        var retried = expect(request(admin, base, "POST", input, true), 201);
        assertThat(retried.path("id")).isEqualTo(created.path("id"));
        assertThat(created.path("source").asText()).isEqualTo("ADMIN");
        assertThat(created.path("status").asText()).isEqualTo("PENDING");
        String idPath = base + "/" + created.path("id").asText();
        var update = Map.of("revision", created.path("revision").asLong(), "status", "PROCESSING", "handlingNote", "正在整理自备歌曲");
        expect(request(admin, idPath, "PUT", update, false), 403);
        var processed = expect(request(admin, idPath, "PUT", update, true), 200);
        assertThat(processed.path("handlingNote").asText()).isEqualTo("正在整理自备歌曲");
        expect(request(admin, idPath, "PUT", update, true), 409);
        expect(request(admin, idPath, "PUT", Map.of("revision", processed.path("revision").asLong(),
                "status", "RESOLVED", "handlingNote", ""), true), 400);
        var list = expect(request(admin, base + "?status=PROCESSING&page=0&size=1", "GET", null, false), 200);
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id")).isEqualTo(created.path("id"));
        var reopened = expect(request(admin, idPath, "PUT", Map.of("revision", processed.path("revision").asLong(),
                "status", "PENDING", "handlingNote", "重新核对版本"), true), 200);
        assertThat(reopened.path("resolvedSongId").isNull()).isTrue();
        assertThat(reopened.path("resolvedAt").isNull()).isTrue();
        expect(request(guest, idPath, "GET", null, false), 401);
        var history = expect(request(admin, "/api/v1/playback-history?period=all", "GET", null, false), 200);
        assertThat(history.path("items").isEmpty()).isTrue();
        assertThat(history.path("totalElements").asLong()).isZero();
        var popular = expect(request(admin, "/api/v1/playback-history/popular", "GET", null, false), 200);
        assertThat(popular.path("totalElements").asLong()).isZero();
        var summary = expect(request(admin, "/api/v1/playback-history/summary", "GET", null, false), 200);
        assertThat(summary.path("totalPlays").asLong()).isZero();
        assertThat(summary.path("effectivePlays").asLong()).isZero();
        var invalid = request(admin, "/api/v1/playback-history?period=invalid", "GET", null, false);
        assertThat(invalid.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        expect(invalid, 400);
    }
}
