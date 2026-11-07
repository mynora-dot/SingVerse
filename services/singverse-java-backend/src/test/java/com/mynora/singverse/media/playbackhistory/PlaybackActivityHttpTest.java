package com.mynora.singverse.media.playbackhistory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Random-port security, collector ownership, actual clocks and TV aggregate integration. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class PlaybackActivityHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("activity-http.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        p.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        p.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
        p.add("singverse.playback-activity.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixture;
    @Autowired PlaybackActivityMapper mapper;
    @MockitoBean MediaSongRepository songs;
    HttpClient admin;

    HttpClient sessionClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    @BeforeEach void setup() throws Exception {
        fixture.update("DELETE FROM playback_attempt"); fixture.update("DELETE FROM playback_history");
        fixture.update("DELETE FROM song_playback_profile");
        fixture.update("UPDATE playback_activity_state SET ready=1,phase='READY'");
        var parsed = new KtvFilenameParser().parse("歌手-真实歌曲.mp4");
        when(songs.get(anyString())).thenAnswer(call -> new Song(call.getArgument(0), "source", "NAS曲库", "song.mp4",
                100L, "2026-11-07T00:00:00Z", "2026-11-07T00:00:00Z", "default", 0, null, parsed, null, null, null));
        admin = sessionClient(); login(admin);
    }
    HttpResponse<String> request(HttpClient client, String path, String method, Object body, boolean csrf, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf) builder.header("X-CSRF-Token", json.readTree(request(client, "/api/v1/auth/csrf", "GET", null, false, null).body()).path("token").asText());
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode response(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }
    void login(HttpClient client) throws Exception {
        response(request(client, "/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true, null), 200);
    }
    JsonNode create(String path) throws Exception {
        return response(request(admin, path, "POST", Map.of("songId", "song", "deviceName", "真实播放测试"), true, null), 201);
    }
    Map<String, Object> event(JsonNode a, long seq, String state, long actual, long elapsed) {
        return Map.of("writeToken", a.path("writeToken").asText(), "eventSeq", seq, "state", state,
                "actualPlayedMs", actual, "elapsedMs", elapsed);
    }
    String path(JsonNode a, boolean mobile) {
        return (mobile ? "/api/v1/mobile/playback-attempts/" : "/api/v1/playback-attempts/") + a.path("attemptId").asText() + "/events";
    }

    @Test void mutationsRequireLoginCsrfAndDoNotAcceptDeviceBearerAsAdministrator() throws Exception {
        var anonymous = sessionClient();
        response(request(anonymous, "/api/v1/mobile/playback-attempts", "POST", Map.of("songId", "song"), true, null), 401);
        response(request(admin, "/api/v1/playback-attempts", "POST", Map.of("songId", "song"), false, null), 403);
        var a = create("/api/v1/mobile/playback-attempts");
        response(request(admin, path(a, true), "POST", event(a, 0, "PLAYING", 0, 0), false, null), 403);
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history", Long.class)).isZero();
    }

    @Test void routeSessionAndWriteTokenCannotBeExchanged() throws Exception {
        var a = create("/api/v1/mobile/playback-attempts");
        var other = sessionClient(); login(other);
        response(request(other, path(a, true), "POST", event(a, 0, "PLAYING", 0, 0), true, null), 403);
        response(request(admin, path(a, false), "POST", event(a, 0, "PLAYING", 0, 0), true, null), 403);
        var wrong = new HashMap<>(event(a, 0, "PLAYING", 0, 0)); wrong.put("writeToken", "x".repeat(43));
        response(request(admin, path(a, true), "POST", wrong, true, null), 403);
        response(request(admin, path(a, true), "POST", event(a, 0, "PLAYING", 0, 0), true, null), 200);
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history", Long.class)).isOne();
    }

    @Test void delayedCreationAndCompletionKeepOriginalDatesAndFullActualCountersOverHttp() throws Exception {
        long started = System.currentTimeMillis() - 30_000, lastActivity = started + 8000;
        var attempt = response(request(admin, "/api/v1/mobile/playback-attempts", "POST",
                Map.of("songId", "song", "startedAt", started), true, null), 201);
        assertThat(attempt.path("startedAt").asLong()).isEqualTo(started);
        assertThat(attempt.path("serverTime").asLong()).isGreaterThan(started);
        var body = new HashMap<>(event(attempt, 0, "COMPLETED", 8000, 8000));
        body.put("durationMs", 8000); body.put("occurredAt", lastActivity); body.put("lastActivityAt", lastActivity);
        var result = response(request(admin, path(attempt, true), "POST", body, true, null), 200);
        assertThat(result.path("effective").asBoolean()).isTrue();
        var row = mapper.attempt(attempt.path("attemptId").asText());
        assertThat(((Number) row.get("actual_played_ms")).longValue()).isEqualTo(8000);
        assertThat(((Number) row.get("effective_at")).longValue()).isEqualTo(lastActivity);
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(lastActivity);
        response(request(admin, "/api/v1/mobile/playback-attempts", "POST",
                Map.of("songId", "song", "startedAt", "invalid"), true, null), 400);
    }

    @Test void expiredMobileActivityReturnsGoneWithoutResumingHistory() throws Exception {
        var a = create("/api/v1/mobile/playback-attempts");
        response(request(admin, path(a, true), "POST", event(a, 0, "PLAYING", 0, 0), true, null), 200);
        fixture.update("UPDATE playback_attempt SET lease_until=? WHERE id=?", System.currentTimeMillis() - 1, a.path("attemptId").asText());
        response(request(admin, path(a, true), "POST", event(a, 1, "PLAYING", 0, 0), true, null), 410);
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history", Long.class)).isOne();
    }

    @Test void mobileEffectiveIsIdempotentAndAdminRouteNeverAddsEffectivePopularity() throws Exception {
        var mobile = create("/api/v1/mobile/playback-attempts");
        response(request(admin, path(mobile, true), "POST", event(mobile, 0, "PLAYING", 0, 0), true, null), 200);
        // Deterministic server-time fixture, rather than sleeping thirty seconds in the HTTP test.
        fixture.update("UPDATE playback_attempt SET started_at=started_at-30000,client_started_at=client_started_at-30000 WHERE id=?", mobile.path("attemptId").asText());
        var effective = response(request(admin, path(mobile, true), "POST", event(mobile, 1, "PLAYING", 30_000, 30_000), true, null), 200);
        assertThat(effective.path("effective").asBoolean()).isTrue();
        response(request(admin, path(mobile, true), "POST", event(mobile, 1, "PLAYING", 30_000, 30_000), true, null), 200);
        var preview = create("/api/v1/playback-attempts");
        response(request(admin, path(preview, false), "POST", event(preview, 0, "PLAYING", 0, 0), true, null), 200);
        fixture.update("UPDATE playback_attempt SET started_at=started_at-30000,client_started_at=client_started_at-30000 WHERE id=?", preview.path("attemptId").asText());
        var previewEnd = response(request(admin, path(preview, false), "POST", event(preview, 1, "COMPLETED", 30_000, 30_000), true, null), 200);
        assertThat(previewEnd.path("effective").asBoolean()).isFalse();
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history WHERE effective_at IS NOT NULL", Long.class)).isOne();
        assertThat(((Number) mapper.profile("song").get("heat")).doubleValue()).isEqualTo(0.05);
    }

    @Test void pausedLeaseDoesNotRefreshRecentPlayAndImpossibleClockIsRejectedAtHttpBoundary() throws Exception {
        var a = create("/api/v1/mobile/playback-attempts");
        response(request(admin, path(a, true), "POST", event(a, 0, "PLAYING", 0, 0), true, null), 200);
        long played = ((Number) mapper.profile("song").get("last_played_at")).longValue();
        response(request(admin, path(a, true), "POST", event(a, 1, "PLAYING", 90_000, 1000), true, null), 400);
        fixture.update("UPDATE playback_attempt SET started_at=started_at-15000,client_started_at=client_started_at-15000 WHERE id=?", a.path("attemptId").asText());
        response(request(admin, path(a, true), "POST", event(a, 1, "PAUSED", 0, 15_000), true, null), 200);
        var row = mapper.attempt(a.path("attemptId").asText());
        assertThat(((Number) row.get("lease_until")).longValue()).isGreaterThan(System.currentTimeMillis() + 115_000);
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(played);
    }

    @Test void televisionUsesOnlyCurrentServerAttemptAndActualCountersRatherThanPosition() throws Exception {
        var guest = HttpClient.newHttpClient();
        var pending = response(request(guest, "/api/v1/device-authorizations", "POST", Map.of("name", "客厅"), false, null), 200);
        String grant = pending.path("id").asText();
        response(request(admin, "/api/v1/admin/device-authorizations/" + grant + "/approve", "POST", Map.of("code", pending.path("code").asText()), true, null), 200);
        String token = response(request(guest, "/api/v1/device-authorizations/" + grant + "/poll", "POST", Map.of("pollSecret", pending.path("pollSecret").asText()), false, null), 200).path("token").asText();
        var session = response(request(guest, "/api/v1/tv/sessions", "POST", null, false, token), 200);
        String base = "/api/v1/tv/sessions/" + session.path("id").asText();
        var added = response(request(guest, base + "/commands", "POST", Map.of("action", "ADD", "songId", "song", "requestId", UUID.randomUUID().toString(), "expectedRevision", session.path("revision").asLong()), false, token), 200);
        added = response(request(guest, base + "/commands", "POST", Map.of("action", "ADD", "songId", "next-song", "requestId", UUID.randomUUID().toString(), "expectedRevision", added.path("revision").asLong()), false, token), 200);
        var started = response(request(guest, base + "/commands", "POST", Map.of("action", "START", "requestId", UUID.randomUUID().toString(), "expectedRevision", added.path("revision").asLong()), false, token), 200);
        String attempt = started.path("playback").path("attemptId").asText();
        Map<String, Object> report = Map.of("attemptId", attempt, "eventSeq", 0, "state", "PLAYING", "positionMs", 170_000, "actualPlayedMs", 0, "elapsedMs", 0);
        response(request(guest, base + "/playback-events", "POST", report, false, token), 200);
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history", Long.class)).isOne();
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history WHERE effective_at IS NOT NULL", Long.class)).isZero();
        var stale = new HashMap<>(report); stale.put("attemptId", "old"); stale.put("eventSeq", 1);
        response(request(guest, base + "/playback-events", "POST", stale, false, token), 409);
        assertThat(mapper.attempt(attempt).get("owner_session_id")).isEqualTo(session.path("id").asText());
        long last = ((Number) mapper.profile("song").get("last_played_at")).longValue();
        fixture.update("UPDATE playback_attempt SET lease_until=? WHERE id=?", System.currentTimeMillis() - 1, attempt);
        var expired = new HashMap<>(report); expired.put("eventSeq", 1);
        response(request(guest, base + "/playback-events", "POST", expired, false, token), 410);
        var ended = new HashMap<>(report); ended.put("eventSeq", 2); ended.put("state", "ENDED");
        ended.put("actualPlayedMs", 180_000); ended.put("elapsedMs", 180_000);
        var completed = response(request(guest, base + "/playback-events", "POST", ended, false, token), 200);
        assertThat(completed.path("playback").path("state").asText()).isEqualTo("ENDED");
        assertThat(completed.path("queue").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(mapper.attempt(attempt).get("state")).isEqualTo("INTERRUPTED");
        assertThat(((Number) mapper.attempt(attempt).get("actual_played_ms")).longValue()).isZero();
        assertThat(mapper.history(mapper.attempt(attempt).get("history_id").toString()).get("status")).isEqualTo("INTERRUPTED");
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(last);
        assertThat(fixture.queryForObject("SELECT COUNT(*) FROM playback_history WHERE effective_at IS NOT NULL", Long.class)).isZero();
        var next = response(request(guest, base + "/commands", "POST", Map.of("action", "START", "requestId", UUID.randomUUID().toString(), "expectedRevision", completed.path("revision").asLong()), false, token), 200);
        assertThat(next.path("playback").path("songId").asText()).isEqualTo("next-song");
        assertThat(next.path("playback").path("attemptId").asText()).isNotEqualTo(attempt);
        assertThat(mapper.attempt(attempt).get("state")).isEqualTo("INTERRUPTED");
    }
}
