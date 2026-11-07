package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.MediaSongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Real HTTP/security, migrated SQLite, filesystem publication and asynchronous cleanup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class SongCacheHttpTest {
    static final String BASE = "/api/v1/transcoding/cache";
    static final long BUDGET = 64L * 1024 * 1024;
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("cache-http.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        p.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        p.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        p.add("singverse.transcoding.cache-minimum-free-bytes", () -> 0);
        p.add("singverse.classification.worker-enabled", () -> false);
        p.add("singverse.playback-activity.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired SongCacheService cache;
    @Autowired SongCacheMapper mapper;
    @Autowired JdbcTemplate fixture;
    @MockitoBean MediaSongRepository songs;
    HttpClient admin;

    HttpClient session() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> request(HttpClient client, String path, String method, Object body, boolean csrf) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf) b.header("X-CSRF-Token", json.readTree(request(client, "/api/v1/auth/csrf", "GET", null, false).body()).path("token").asText());
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode response(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(status);
        return r.body().isBlank() ? json.nullNode() : json.readTree(r.body());
    }
    JsonNode get(String path) throws Exception { return response(request(admin, path, "GET", null, false), 200); }
    JsonNode mutate(String path, String method, Object body, int status) throws Exception {
        return response(request(admin, path, method, body, true), status);
    }
    ObjectNode settings() throws Exception { return (ObjectNode) get(BASE).path("settings"); }
    ObjectNode legacySettings(String policy) throws Exception {
        return json.createObjectNode().put("maxBytes", BUDGET).put("policy", policy).put("ttlHours", 168)
                .put("revision", settings().path("revision").asLong());
    }
    String cached(String song) throws Exception {
        try (var original = cache.original(song, "stable-source-fingerprint", 8, true)) {
            assertThat(original).isNotNull();
            try (var writer = original.writer(0, 7, () -> {})) { writer.write(new byte[8], 0, 8); }
        }
        return mapper.page(0, 100, System.currentTimeMillis() - 604800000L, System.currentTimeMillis()).stream()
                .filter(row -> song.equals(row.get("song_id"))).findFirst().orElseThrow().get("id").toString();
    }
    JsonNode completed(String id) throws Exception {
        cache.awaitMaintenance();
        JsonNode result = get(BASE + "/cleanup-jobs/" + id);
        assertThat(result.path("state").asText()).isIn("SUCCEEDED", "PARTIAL", "BLOCKED", "CANCELLED", "FAILED");
        return result;
    }

    @BeforeEach void setup() throws Exception {
        cache.awaitMaintenance();
        fixture.update("DELETE FROM playback_attempt");
        fixture.update("DELETE FROM playback_history");
        fixture.update("UPDATE song_playback_profile SET pinned=0,last_played_at=NULL");
        fixture.update("UPDATE playback_activity_state SET ready=1,phase='READY'");
        cache.clear(); cache.awaitMaintenance();
        fixture.update("DELETE FROM song_playback_profile");
        var parsed = new KtvFilenameParser().parse("歌手-缓存歌曲.mp4");
        when(songs.get(anyString())).thenAnswer(call -> new Song(call.getArgument(0), "source", "隔离曲库", "song.mp4",
                8L, "2026-11-07T00:00:00Z", "2026-11-07T00:00:00Z", "default", 0, null, parsed, null, null, null));
        admin = session();
        response(request(admin, "/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true), 200);
        mutate(BASE + "/settings", "PUT", legacySettings("MANUAL"), 200);
        cache.awaitMaintenance();
    }

    @Test void originalFourFieldSettingsStayCompatibleAndAliasesPersistHybrid() throws Exception {
        var custom = legacySettings("HYBRID").put("highWatermarkPercent", 94).put("lowWatermarkPercent", 71);
        mutate(BASE + "/settings", "PUT", custom, 200);
        for (var alias : Set.of("LRU", "LFU", "TTL")) {
            var saved = mutate(BASE + "/settings", "PUT", legacySettings(alias), 200);
            assertThat(saved.path("policy").asText()).isEqualTo("HYBRID");
            assertThat(saved.path("highWatermarkPercent").asInt()).isEqualTo(94);
            assertThat(saved.path("lowWatermarkPercent").asInt()).isEqualTo(71);
        }
    }

    @Test void watermarksRequireIntegersValidOrderAndCurrentRevision() throws Exception {
        var stale = legacySettings("HYBRID");
        mutate(BASE + "/settings", "PUT", stale, 200);
        mutate(BASE + "/settings", "PUT", stale, 409);
        mutate(BASE + "/settings", "PUT", legacySettings("HYBRID").put("highWatermarkPercent", 101), 400);
        mutate(BASE + "/settings", "PUT", legacySettings("HYBRID").put("highWatermarkPercent", 70).put("lowWatermarkPercent", 70), 400);
        mutate(BASE + "/settings", "PUT", legacySettings("HYBRID").put("lowWatermarkPercent", 0), 400);
        mutate(BASE + "/settings", "PUT", legacySettings("HYBRID").put("lowWatermarkPercent", 70.5), 400);
        mutate(BASE + "/settings", "PUT", legacySettings("HYBRID").put("unexpected", true), 400);
    }

    @Test void pinsCanPrecedeCacheAndUseIndependentCompareAndSetRevisions() throws Exception {
        var pinned = mutate(BASE + "/songs/future-song/pin", "PUT", Map.of("pinned", true, "revision", 0), 200);
        assertThat(pinned.path("revision").asLong()).isEqualTo(1);
        var list = get(BASE + "/pins?page=0&size=20");
        assertThat(list.path("totalElements").asLong()).isOne();
        assertThat(list.path("items").get(0).path("songId").asText()).isEqualTo("future-song");
        assertThat(list.path("items").get(0).path("hasCache").asBoolean()).isFalse();
        mutate(BASE + "/songs/future-song/pin", "PUT", Map.of("pinned", false, "revision", 0), 409);
        var unpinned = mutate(BASE + "/songs/future-song/pin", "PUT", Map.of("pinned", false, "revision", 1), 200);
        assertThat(unpinned.path("revision").asLong()).isEqualTo(2);
        assertThat(get(BASE + "/pins").path("totalElements").asLong()).isZero();
    }

    @Test void asynchronousCleanupIsAcceptedQueryableAndTerminalCancellationIsIdempotent() throws Exception {
        String id = cached("removable");
        var accepted = mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR"), 202);
        String jobId = accepted.path("id").asText();
        assertThat(jobId).isNotBlank();
        var result = completed(jobId);
        assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("removed").asInt()).isOne();
        assertThat(result.path("failed").asInt()).isZero();
        assertThat(result.has("skippedByReason")).isTrue();
        assertThat(mapper.find(id)).isNull();
        assertThat(mutate(BASE + "/cleanup-jobs/" + jobId + "/cancel", "POST", Map.of(), 200)).isEqualTo(result);
        response(request(admin, BASE + "/cleanup-jobs/not-a-job", "GET", null, false), 404);
    }

    @Test void ordinaryClearProtectsPinnedAndRecentSongsAndExplicitDeleteOnlyOverridesRecent() throws Exception {
        String pinned = cached("pinned"), recent = cached("recent"), removable = cached("ordinary");
        mutate(BASE + "/songs/pinned/pin", "PUT", Map.of("pinned", true, "revision", 0), 200);
        fixture.update("UPDATE song_playback_profile SET last_played_at=? WHERE song_id='recent'", System.currentTimeMillis());
        var list = get(BASE);
        assertThat(list.path("entryCount").asLong()).isEqualTo(3);
        var cleared = mutate(BASE + "/clear", "POST", Map.of(), 200);
        assertThat(cleared.path("removed").asInt()).isOne();
        assertThat(cleared.path("skippedByReason").path("PINNED").asInt()).isOne();
        assertThat(cleared.path("skippedByReason").path("RECENT_PLAY").asInt()).isOne();
        assertThat(mapper.find(removable)).isNull();
        mutate(BASE + "/" + recent, "DELETE", null, 409);
        mutate(BASE + "/" + recent + "?confirmRecent=true", "DELETE", null, 204);
        assertThat(mapper.find(recent)).isNull();
        mutate(BASE + "/" + pinned + "?confirmRecent=true", "DELETE", null, 409);
        assertThat(mapper.find(pinned)).isNotNull();
    }

    @Test void explicitCleanupDeleteSupportsRecentConfirmationButRejectsInvalidTargets() throws Exception {
        String id = cached("recent-job");
        fixture.update("UPDATE song_playback_profile SET last_played_at=? WHERE song_id='recent-job'", System.currentTimeMillis());
        var blocked = mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "DELETE", "entryId", id), 202);
        assertThat(completed(blocked.path("id").asText()).path("state").asText()).isEqualTo("BLOCKED");
        var allowed = mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "DELETE", "entryId", id, "confirmRecent", true), 202);
        assertThat(completed(allowed.path("id").asText()).path("removed").asInt()).isOne();
        mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR", "confirmRecent", true), 400);
        mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "DELETE", "entryId", "bad"), 400);
        mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR", "unexpected", true), 400);
    }

    @Test void statisticsNotReadyBlocksOrdinaryClearUntilExplicitPerEntryConfirmation() throws Exception {
        String id = cached("unknown-recent");
        fixture.update("UPDATE playback_activity_state SET ready=0,phase='PREPARE'");
        var job = mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR"), 202);
        assertThat(completed(job.path("id").asText()).path("skippedByReason").path("STATISTICS_NOT_READY").asInt()).isOne();
        assertThat(mapper.find(id)).isNotNull();
        mutate(BASE + "/" + id, "DELETE", null, 409);
        mutate(BASE + "/" + id + "?confirmRecent=true", "DELETE", null, 204);
        assertThat(mapper.find(id)).isNull();
    }

    @Test void everyNewMutationRequiresAdministratorCookieAndCsrf() throws Exception {
        var anonymous = session();
        response(request(anonymous, BASE + "/pins", "GET", null, false), 401);
        response(request(anonymous, BASE + "/songs/other/pin", "PUT", Map.of("pinned", true, "revision", 0), true), 401);
        response(request(admin, BASE + "/songs/other/pin", "PUT", Map.of("pinned", true, "revision", 0), false), 403);
        response(request(admin, BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR"), false), 403);
        var job = mutate(BASE + "/cleanup-jobs", "POST", Map.of("mode", "CLEAR"), 202);
        completed(job.path("id").asText());
        response(request(admin, BASE + "/cleanup-jobs/" + job.path("id").asText() + "/cancel", "POST", Map.of(), false), 403);
        response(request(anonymous, BASE + "/cleanup-jobs/" + job.path("id").asText(), "GET", null, false), 401);
    }
}
