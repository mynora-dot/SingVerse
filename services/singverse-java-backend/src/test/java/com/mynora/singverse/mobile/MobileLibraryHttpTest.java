package com.mynora.singverse.mobile;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class MobileLibraryHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("mobile.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
        p.add("singverse.compute.base-url", () -> "");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TvLibrary library;
    HttpClient client;
    static final String API = "/api/v1/mobile/library/songs";
    @BeforeEach void clients() {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> request(String path, String method, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (!method.equals("GET")) b.header("X-CSRF-Token", json.readTree(request("/api/v1/auth/csrf", "GET", null).body()).path("token").asText());
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path, String method, Object body) throws Exception {
        var r = request(path, method, body);
        assertThat(r.statusCode()).withFailMessage(r.body()).isBetween(200, 201);
        return json.readTree(r.body());
    }
    void login() throws Exception { ok("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin")); }

    @Test void anonymousAndTvBearerCannotReadMobileLibrary() throws Exception {
        for (String path : List.of(API, API + "/missing", API + "/missing/text-tracks")) {
            assertThat(request(path, "GET", null).statusCode()).isEqualTo(401);
        }
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + API)).header("Authorization", "Bearer unrelated-tv-token").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test void realScannedLibrarySupportsFiltersStableIdentityAndRestrictedText() throws Exception {
        login();
        Path root = Files.createTempDirectory(temp, "media-");
        Files.writeString(root.resolve("周杰伦-晴天.flac"), "original audio");
        Files.writeString(root.resolve("周杰伦-晴天.mkv"), "original video");
        Files.writeString(root.resolve("周杰伦-晴天.lrc"), "[00:01.00]第一句\n[00:03.00]第二句");
        var source = ok("/api/v1/media-sources", "POST", Map.of("name", "手机测试曲库", "type", "LOCAL", "location", root.toString()));
        ok("/api/v1/media-sources/" + source.path("id").asText() + "/scan", "POST", null);
        library.invalidate();
        var result = ok(API + "?q=zjl&page=0&size=1", "GET", null);
        assertThat(result.path("totalElements").asInt()).isEqualTo(2);
        assertThat(result.path("items").size()).isEqualTo(1);
        var first = result.at("/items/0");
        String firstId = first.path("id").asText();
        var second = ok(API + "?q=zjl&page=1&size=1", "GET", null).at("/items/0");
        assertThat(second.path("id").asText()).isNotEqualTo(firstId);
        assertThat(Set.of(first.path("mediaType").asText(), second.path("mediaType").asText())).containsExactlyInAnyOrder("AUDIO", "VIDEO");
        assertThat(first.path("revision").asText()).isNotBlank();
        assertThat(first.has("relativePath")).isFalse();
        assertThat(first.path("playability").asText()).isEqualTo("UNKNOWN");
        assertThat(ok(API + "/" + firstId, "GET", null)).isEqualTo(first);
        assertThat(ok(API + "?q=qingtian", "GET", null).path("totalElements").asInt()).isEqualTo(2);
        String artistId = ok("/api/v1/artists?q=zjl", "GET", null).at("/items/0/id").asText();
        assertThat(ok(API + "?artistId=" + artistId, "GET", null).path("totalElements").asInt()).isEqualTo(2);
        var tag = ok("/api/v1/classification/tags", "POST", Map.of("dimension", "CUSTOM", "name", "手机筛选", "aliases", List.of(), "active", true, "revision", 0));
        String tagId = tag.path("id").asText();
        ok("/api/v1/classification/songs/" + firstId, "PUT", Map.of("requestId", UUID.randomUUID().toString(), "revision", 0, "dimensions", Map.of("CUSTOM", List.of(tagId)), "lockedDimensions", List.of()));
        library.invalidate();
        assertThat(ok(API + "?tagIds=" + tagId, "GET", null).path("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(API + "?unclassified=true", "GET", null).path("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(API + "?q=no-such-song", "GET", null).path("items")).isEmpty();
        for (String suffix : List.of("?page=-1", "?size=101", "?tagIds=missing")) assertThat(request(API + suffix, "GET", null).statusCode()).isEqualTo(400);
        assertThat(request(API + "/missing", "GET", null).statusCode()).isEqualTo(404);
        var texts = ok(API + "/" + firstId + "/text-tracks", "GET", null);
        assertThat(texts.at("/items/0/format").asText()).isEqualTo("lrc");
        assertThat(texts.at("/items/0/text").asText()).contains("第一句");
        Files.delete(root.resolve("周杰伦-晴天.lrc"));
        Files.createSymbolicLink(root.resolve("周杰伦-晴天.lrc"), Files.writeString(temp.resolve("private.lrc"), "must not leak"));
        assertThat(ok(API + "/" + firstId + "/text-tracks", "GET", null).path("items")).isEmpty();
        Files.delete(root.resolve("周杰伦-晴天.lrc"));
        Files.writeString(root.resolve("周杰伦-晴天.lrc"), "x".repeat(131073));
        assertThat(ok(API + "/" + firstId + "/text-tracks", "GET", null).path("warnings")).isNotEmpty();
        assertThat(Files.readString(root.resolve("周杰伦-晴天.flac"))).isEqualTo("original audio");
        assertThat(request("/api/v1/tv/library/songs", "GET", null).statusCode()).isEqualTo(401);
    }
}
