package com.mynora.singverse.media.playbackhistory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.MediaSongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Real migrated SQLite/XML transactions; only catalog snapshots and the clock are controlled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sqlite")
@DirtiesContext
class PlaybackActivityServiceTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("activity.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        p.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        p.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
        p.add("singverse.playback-activity.worker-enabled", () -> false);
    }
    @Autowired PlaybackActivityMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate fixture;
    @MockitoBean MediaSongRepository songs;
    MutableClock clock;
    PlaybackActivityService service;

    static final class MutableClock extends Clock {
        long now = Instant.parse("2026-11-07T10:00:00Z").toEpochMilli();
        void advance(long ms) { now += ms; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @BeforeEach void setup() {
        fixture.update("DELETE FROM playback_attempt");
        fixture.update("DELETE FROM playback_history");
        fixture.update("DELETE FROM song_playback_profile");
        fixture.update("UPDATE playback_activity_state SET ready=1,phase='READY',cursor_started_at=0,cursor_id=''");
        clock = new MutableClock();
        service = new PlaybackActivityService(mapper, songs, json, transactions, clock);
        var parsed = new KtvFilenameParser().parse("歌手-真实歌曲.mp4");
        when(songs.get(anyString())).thenAnswer(call -> new Song(call.getArgument(0), "source", "NAS曲库",
                "song.mp4", 100L, "2026-11-07T00:00:00Z", "2026-11-07T00:00:00Z", "default", 0,
                null, parsed, null, null, null));
    }

    Map<String, Object> create(String type, String song) { return service.create(type, "owner", song, "隔离设备"); }
    Map<String, Object> report(Map<String, Object> attempt, long seq, String state, long actual, long elapsed, Long duration) {
        return report("MOBILE", "owner", attempt, seq, state, actual, elapsed, duration);
    }
    Map<String, Object> report(String type, String owner, Map<String, Object> attempt, long seq, String state,
            long actual, long elapsed, Long duration) {
        var body = new HashMap<String, Object>();
        body.put("eventSeq", seq); body.put("state", state); body.put("actualPlayedMs", actual);
        body.put("elapsedMs", elapsed); body.put("writeToken", attempt.get("writeToken"));
        if (duration != null) body.put("durationMs", duration);
        return service.report(type, owner, attempt.get("attemptId").toString(), json.valueToTree(body));
    }
    long count(String table) {
        return switch (table) {
            case "history" -> fixture.queryForObject("SELECT COUNT(*) FROM playback_history", Long.class);
            case "effective" -> fixture.queryForObject("SELECT COUNT(*) FROM playback_history WHERE effective_at IS NOT NULL", Long.class);
            default -> throw new IllegalArgumentException(table);
        };
    }
    double heat(String song) { return ((Number) mapper.profile(song).get("heat")).doubleValue(); }
    Map<String, Object> timed(Map<String, Object> a, long seq, String state, long actual, long elapsed,
            long occurred, Long activity) {
        var event = new HashMap<String, Object>();
        event.put("writeToken", a.get("writeToken")); event.put("eventSeq", seq); event.put("state", state);
        event.put("actualPlayedMs", actual); event.put("elapsedMs", elapsed);
        event.put("occurredAt", occurred); event.put("lastActivityAt", activity);
        return event;
    }

    @Test void queuedPausedSnapshotUsesOriginalActivityEffectiveAndLeaseTimes() {
        long started = clock.now;
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        clock.advance(90_000);
        var event = timed(a, 1, "PAUSED", 30_000, 60_000, started + 60_000, started + 30_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(), json.valueToTree(event));
        var row = mapper.attempt(a.get("attemptId").toString());
        assertThat(((Number) row.get("lease_until")).longValue()).isEqualTo(started + 180_000);
        assertThat(((Number) row.get("effective_at")).longValue()).isEqualTo(started + 30_000);
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(started + 30_000);
        assertThat(((Number) mapper.profile("song").get("heat_updated_at")).longValue()).isEqualTo(started + 30_000);
        clock.advance(10_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(), json.valueToTree(event));
        assertThat(((Number) mapper.attempt(a.get("attemptId").toString()).get("lease_until")).longValue()).isEqualTo(started + 180_000);
        assertThat(heat("song")).isEqualTo(0.05);
    }

    @Test void slowCreationKeepsOriginalClockAndFullShortTrackListeningBeforeRegistration() {
        long started = clock.now - 40_000;
        var a = service.create("MOBILE", "owner", "short", "手机", started);
        assertThat(a).containsEntry("startedAt", started).containsEntry("serverTime", clock.now);
        var event = timed(a, 0, "COMPLETED", 8_000, 8_000, started + 8_000, started + 8_000);
        event.put("durationMs", 8_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(), json.valueToTree(event));
        var row = mapper.attempt(a.get("attemptId").toString());
        assertThat(((Number) row.get("actual_played_ms")).longValue()).isEqualTo(8_000);
        assertThat(((Number) row.get("effective_at")).longValue()).isEqualTo(started + 8_000);
        assertThat(((Number) mapper.profile("short").get("last_played_at")).longValue()).isEqualTo(started + 8_000);
        assertThat(heat("short")).isEqualTo(0.05);
    }

    @Test void actualResumeImmediatelyProtectsButSilentHeartbeatsCannotAdvanceRecentActivity() {
        long started = clock.now;
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        clock.advance(15_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 1, "PREPARING", 0, 15_000, clock.now, started)));
        clock.advance(15_000);
        assertThatThrownBy(() -> service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 2, "BUFFERING", 0, 30_000, clock.now, clock.now)))).hasMessageContaining("400");
        service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 2, "PLAYING", 0, 30_000, clock.now, clock.now)));
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(clock.now);
        assertThat(heat("song")).isZero();
        assertThat(count("history")).isOne();
    }

    @Test void futureForgedRegressingAndInconsistentOccurrenceClocksAreRejectedAtomically() {
        var a = create("MOBILE", "song");
        assertThatThrownBy(() -> service.create("MOBILE", "owner", "other", "手机", clock.now - 86_400_001)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.create("MOBILE", "owner", "other", "手机", clock.now + 5001)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 0, "PLAYING", 0, 0, clock.now + 5001, clock.now)))).hasMessageContaining("400");
        clock.advance(20_000);
        assertThatThrownBy(() -> service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 0, "PLAYING", 0, 0, clock.now, clock.now)))).hasMessageContaining("400");
        assertThat(count("history")).isZero();
        assertThat(((Number) mapper.attempt(a.get("attemptId").toString()).get("event_seq")).longValue()).isEqualTo(-1);
    }

    @Test void televisionBindsClientClockOnceRatherThanCountingPreparationDelay() {
        service.startTv("tv-clock", "session", "song", "电视", "亲友");
        clock.advance(10_000);
        long origin = clock.now;
        var event = timed(Map.of("writeToken", "unused"), 0, "PLAYING", 0, 0, origin, origin);
        event.put("startedAt", origin);
        service.reportTv("session", "tv-clock", json.valueToTree(event));
        clock.advance(5000);
        event.putAll(Map.of("eventSeq", 1, "actualPlayedMs", 5000, "elapsedMs", 5000,
                "occurredAt", clock.now, "lastActivityAt", clock.now));
        service.reportTv("session", "tv-clock", json.valueToTree(event));
        assertThat(((Number) mapper.attempt("tv-clock").get("actual_played_ms")).longValue()).isEqualTo(5000);
        event.put("startedAt", origin + 1); event.put("eventSeq", 2);
        assertThatThrownBy(() -> service.reportTv("session", "tv-clock", json.valueToTree(event))).hasMessageContaining("400");
    }

    @Test void toleratedClientClockAheadKeepsPausedActivityFrozenAfterServerClamping() {
        long serverStart = clock.now, origin = clock.now + 4000;
        var a = service.create("MOBILE", "owner", "song", "手机", origin);
        service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 0, "PLAYING", 0, 0, origin, origin)));
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(serverStart);
        clock.advance(15_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 1, "PAUSED", 0, 15_000, origin + 15_000, origin)));
        clock.advance(15_000);
        service.report("MOBILE", "owner", a.get("attemptId").toString(),
                json.valueToTree(timed(a, 2, "BUFFERING", 0, 30_000, origin + 30_000, origin)));
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(serverStart);
        assertThat(((Number) mapper.attempt(a.get("attemptId").toString()).get("last_activity_at")).longValue()).isEqualTo(serverStart);
    }

    @Test void errorCanRecoverWithinItsOriginalOccurrenceLeaseButExpiresAfterOneHundredTwentySeconds() {
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        long started = clock.now;
        clock.advance(30_000);
        report(a, 1, "ERROR", 30_000, 30_000, null);
        assertThat(((Number) mapper.attempt(a.get("attemptId").toString()).get("lease_until")).longValue()).isEqualTo(started + 150_000);
        clock.advance(119_999);
        report(a, 2, "PLAYING", 30_000, 149_999, null);
        report(a, 3, "ERROR", 30_000, 149_999, null);
        clock.advance(120_001);
        assertThatThrownBy(() -> report(a, 4, "PLAYING", 30_000, 270_000, null)).hasMessageContaining("410");
        service.maintain();
        assertThat(mapper.attempt(a.get("attemptId").toString()).get("state")).isEqualTo("INTERRUPTED");
        assertThat(count("history")).isOne(); assertThat(count("effective")).isOne();
        assertThat(heat("song")).isEqualTo(.05);
    }

    void heatHistory(String song, long time, boolean applied) {
        fixture.update("INSERT INTO playback_history(id,song_id,title,artists_json,client_type,started_at,played_ms,status,"
                        + "effective_at,heat_applied,activity_prepared,last_activity_at) VALUES(?,?,'乱序记录','[]','MOBILE',?,30000,'STOPPED',?,?,1,?)",
                UUID.randomUUID().toString(), song, time - 30_000, time, applied ? 1 : 0, time);
    }

    @Test void saturatedHeatLateArrivalExactlyMatchesChronologicalFoldAndExcludesUnappliedFutureRows() {
        long old = clock.now - Duration.ofDays(14).toMillis();
        fixture.update("UPDATE playback_activity_state SET ready=0,phase='AGGREGATE'");
        // Reference: all increments arrive in occurrence order.
        for (int i = 0; i < 26; i++) heatHistory("reference", old, false);
        heatHistory("reference", clock.now, false);
        // Late old increment arrives after a more recent increment was already capped and committed.
        for (int i = 0; i < 25; i++) heatHistory("late", old, true);
        heatHistory("late", clock.now, true);
        heatHistory("late", old, false);
        fixture.update("INSERT INTO song_playback_profile(song_id,heat,heat_updated_at) VALUES('late',0.55,?)", clock.now);
        // Rebuild must not pre-apply a future pending increment; its own aggregation turn adds it once.
        for (int i = 0; i < 25; i++) heatHistory("pending", old, true);
        heatHistory("pending", old - 1000, false);
        heatHistory("pending", clock.now, false);
        fixture.update("INSERT INTO song_playback_profile(song_id,heat,heat_updated_at) VALUES('pending',1,?)", old);
        while (!service.isReady()) service.backfillBatch();
        assertThat(heat("reference")).isCloseTo(.55, within(1e-12));
        assertThat(heat("late")).isEqualTo(heat("reference"));
        assertThat(heat("pending")).isEqualTo(heat("reference"));
        service.backfillBatch();
        assertThat(heat("late")).isEqualTo(heat("reference"));
    }

    @Test void preparingCreatesNoHistoryOrRecentProtectionUntilRealPlaybackStarts() {
        var a = create("MOBILE", "song");
        report(a, 0, "PREPARING", 0, 0, null);
        clock.advance(10_000);
        report(a, 1, "BUFFERING", 0, 10_000, null);
        assertThat(count("history")).isZero();
        assertThat(mapper.profile("song").get("last_played_at")).isNull();
        assertThat(mapper.attempt(a.get("attemptId").toString()).get("started_playing_at")).isNull();
        report(a, 2, "PLAYING", 0, 10_000, 180_000L);
        assertThat(count("history")).isOne();
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(clock.now);
    }

    @Test void effectiveThresholdIsOnceAcrossRetriesDuplicatesAndLaterHeartbeats() {
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, 180_000L);
        clock.advance(29_999); report(a, 1, "PLAYING", 29_999, 29_999, null);
        assertThat(count("effective")).isZero();
        clock.advance(1); report(a, 2, "PLAYING", 30_000, 30_000, null);
        assertThat(count("effective")).isOne(); assertThat(heat("song")).isEqualTo(0.05);
        report(a, 2, "PLAYING", 30_000, 30_000, null);
        report(a, 3, "ERROR", 30_000, 30_000, null);
        clock.advance(20_000); report(a, 4, "PLAYING", 30_000, 50_000, null);
        clock.advance(5_000); report(a, 5, "PLAYING", 35_000, 55_000, null);
        assertThat(count("history")).isOne(); assertThat(count("effective")).isOne();
        assertThat(heat("song")).isEqualTo(0.05);
    }

    @Test void pausedAndBufferingHeartbeatsRenewLeaseWithoutMovingActualActivity() {
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        long played = clock.now;
        clock.advance(15_000); report(a, 1, "PAUSED", 0, 15_000, null);
        clock.advance(15_000); report(a, 2, "BUFFERING", 0, 30_000, null);
        var row = mapper.attempt(a.get("attemptId").toString());
        assertThat(((Number) row.get("lease_until")).longValue()).isEqualTo(clock.now + 120_000);
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(played);
        assertThat(heat("song")).isZero();
    }

    @Test void seekPositionCannotProduceHeatAndImpossibleOrRegressingClocksRollback() {
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, 180_000L);
        clock.advance(5_000);
        assertThatThrownBy(() -> report(a, 1, "PLAYING", 150_000, 5_000, null)).hasMessageContaining("400");
        assertThatThrownBy(() -> report(a, 1, "PLAYING", 35_000, 35_000, null)).hasMessageContaining("400");
        report(a, 1, "PLAYING", 5_000, 5_000, null);
        assertThatThrownBy(() -> report(a, 2, "PLAYING", 4_000, 5_000, null)).hasMessageContaining("400");
        assertThat(count("effective")).isZero();
        assertThat(((Number) mapper.attempt(a.get("attemptId").toString()).get("actual_played_ms")).longValue()).isEqualTo(5_000);
    }

    @Test void shortTrackNeedsCompleteActualListeningAndUnknownDurationDoesNotQualify() {
        var a = create("MOBILE", "short"); report(a, 0, "PLAYING", 0, 0, 8_000L);
        clock.advance(8_000); report(a, 1, "COMPLETED", 8_000, 8_000, null);
        assertThat(heat("short")).isEqualTo(0.05);
        var b = create("MOBILE", "skipped"); report(b, 0, "PLAYING", 0, 0, 8_000L);
        clock.advance(8_000); report(b, 1, "SKIPPED", 8_000, 8_000, null);
        var c = create("MOBILE", "unknown"); report(c, 0, "PLAYING", 0, 0, null);
        clock.advance(8_000); report(c, 1, "COMPLETED", 8_000, 8_000, null);
        assertThat(count("effective")).isOne(); assertThat(heat("skipped")).isZero(); assertThat(heat("unknown")).isZero();
    }

    @Test void administratorPreviewUpdatesRecentActivityAndHistoryButNeverHeatOrFrequency() {
        var a = create("ADMIN", "song"); report("ADMIN", "owner", a, 0, "PLAYING", 0, 0, 60_000L);
        clock.advance(60_000); report("ADMIN", "owner", a, 1, "COMPLETED", 60_000, 60_000, null);
        assertThat(count("history")).isOne(); assertThat(count("effective")).isZero(); assertThat(heat("song")).isZero();
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(clock.now);
    }

    @Test void ownerRouteAndRandomTokenAllBindTheAttemptAndFailuresDoNotWrite() {
        var a = create("MOBILE", "song");
        assertThatThrownBy(() -> report("MOBILE", "another-session", a, 0, "PLAYING", 0, 0, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> report("ADMIN", "owner", a, 0, "PLAYING", 0, 0, null)).hasMessageContaining("403");
        var stolen = new HashMap<>(a); stolen.put("writeToken", "x".repeat(43));
        assertThatThrownBy(() -> report(stolen, 0, "PLAYING", 0, 0, null)).hasMessageContaining("403");
        assertThat(count("history")).isZero();
    }

    @Test void stoppedAttemptCannotResumeAndExpiryPreservesKnownActualTimeWithoutFabrication() {
        var stopped = create("MOBILE", "stopped"); report(stopped, 0, "PLAYING", 0, 0, null);
        report(stopped, 1, "STOPPED", 0, 0, null);
        assertThatThrownBy(() -> report(stopped, 2, "PLAYING", 0, 0, null)).hasMessageContaining("409");
        var expired = create("MOBILE", "expired"); report(expired, 0, "PLAYING", 0, 0, null);
        clock.advance(5_000); report(expired, 1, "PLAYING", 5_000, 5_000, null);
        long last = clock.now; clock.advance(120_001); service.maintain();
        var row = mapper.attempt(expired.get("attemptId").toString());
        assertThat(row.get("state")).isEqualTo("INTERRUPTED");
        var h = mapper.history(row.get("history_id").toString());
        assertThat(((Number) h.get("played_ms")).longValue()).isEqualTo(5_000);
        assertThat(((Number) h.get("ended_at")).longValue()).isEqualTo(last);
    }

    @Test void heatIsCappedAtAccumulationAndDecaysWithFourteenDayHalfLife() {
        fixture.update("INSERT INTO song_playback_profile(song_id,heat,heat_updated_at) VALUES('song',1,?)", clock.now);
        clock.advance(Duration.ofDays(14).toMillis());
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        clock.advance(30_000); report(a, 1, "PLAYING", 30_000, 30_000, null);
        assertThat(heat("song")).isCloseTo(0.5 * PlaybackActivityService.decay(30_000) + 0.05, within(1e-12));
        fixture.update("UPDATE song_playback_profile SET heat=1,heat_updated_at=? WHERE song_id='song'", clock.now);
        var b = create("MOBILE", "song"); report(b, 0, "PLAYING", 0, 0, null);
        clock.advance(30_000); report(b, 1, "PLAYING", 30_000, 30_000, null);
        assertThat(heat("song")).isOne();
        assertThat(PlaybackActivityService.decay(Duration.ofDays(14).toMillis())).isEqualTo(0.5);
    }

    @Test void playbackAndBackfillNeverOverwriteConcurrentPinColumns() {
        fixture.update("INSERT INTO song_playback_profile(song_id,pinned,pin_revision) VALUES('song',1,42)");
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        clock.advance(30_000); report(a, 1, "PLAYING", 30_000, 30_000, null);
        var p = mapper.profile("song");
        assertThat(((Number) p.get("pinned")).intValue()).isOne();
        assertThat(((Number) p.get("pin_revision")).longValue()).isEqualTo(42);
    }

    @Test void resumableBackfillPreparesAndAggregatesAtMostTwoHundredPerTick() {
        fixture.update("UPDATE playback_activity_state SET ready=0,phase='PREPARE'");
        long start = clock.now - Duration.ofDays(2).toMillis();
        for (int i = 0; i < 205; i++) fixture.update("INSERT INTO playback_history(id,song_id,title,artists_json,client_type,"
                        + "started_at,played_ms,status,ended_at) VALUES(?,?,'旧记录','[]','TV',?,30000,'STOPPED',?)",
                UUID.randomUUID().toString(), "legacy-" + i, start + i, start + i + 600_000);
        assertThat(service.backfillBatch()).isEqualTo(200); assertThat(service.isReady()).isFalse();
        // Re-create the service to prove progress lives in SQLite, rather than an in-memory cursor.
        service = new PlaybackActivityService(mapper, songs, json, transactions, clock);
        assertThat(service.backfillBatch()).isEqualTo(5); assertThat(service.isReady()).isFalse();
        assertThat(service.backfillBatch()).isEqualTo(200); assertThat(service.isReady()).isFalse();
        assertThat(service.backfillBatch()).isEqualTo(5); assertThat(service.isReady()).isTrue();
        assertThat(service.backfillBatch()).isZero(); assertThat(count("effective")).isEqualTo(205);
        assertThat(heat("legacy-0")).isEqualTo(0.05);
        assertThat(((Number) mapper.profile("legacy-0").get("last_played_at")).longValue()).isEqualTo(start);
        assertThat(((Number) mapper.profile("legacy-0").get("heat_updated_at")).longValue()).isEqualTo(start);
        assertThat(fixture.queryForObject("SELECT effective_at FROM playback_history WHERE song_id='legacy-0'", Long.class)).isEqualTo(start);
    }

    @Test void backfillUsesEffectiveTimeOrderForCappedHeatAndPausedEndDoesNotRefreshActivity() {
        fixture.update("UPDATE playback_activity_state SET ready=0,phase='PREPARE'");
        long old = clock.now - Duration.ofDays(14).toMillis();
        for (int i = 0; i < 25; i++) fixture.update("INSERT INTO playback_history(id,song_id,title,artists_json,client_type,"
                        + "started_at,played_ms,status,effective_at,activity_prepared) VALUES(?,'song','旧记录','[]','TV',?,30000,'STOPPED',?,1)",
                UUID.randomUUID().toString(), old + i, old);
        var a = create("MOBILE", "song"); report(a, 0, "PLAYING", 0, 0, null);
        clock.advance(30_000); report(a, 1, "PLAYING", 30_000, 30_000, null);
        long activity = clock.now;
        clock.advance(60_000); report(a, 2, "STOPPED", 30_000, 90_000, null);
        while (!service.isReady()) service.backfillBatch();
        assertThat(heat("song")).isCloseTo(0.5 * PlaybackActivityService.decay(30_000) + 0.05, within(1e-12));
        assertThat(((Number) mapper.profile("song").get("last_played_at")).longValue()).isEqualTo(activity);
    }

    @Test void tvRetryKeepsOneHistoryButExplicitStopPreventsLateResurrection() {
        String id = UUID.randomUUID().toString(); service.startTv(id, "tv-session", "song", "客厅", "亲友");
        service.reportTv("tv-session", id, json.valueToTree(Map.of("eventSeq", 0, "state", "PLAYING", "actualPlayedMs", 0, "elapsedMs", 0)));
        clock.advance(30_000);
        service.reportTv("tv-session", id, json.valueToTree(Map.of("eventSeq", 1, "state", "ERROR", "actualPlayedMs", 30_000, "elapsedMs", 30_000)));
        service.reportTv("tv-session", id, json.valueToTree(Map.of("eventSeq", 2, "state", "PLAYING", "actualPlayedMs", 30_000, "elapsedMs", 30_000)));
        assertThat(count("history")).isOne(); assertThat(count("effective")).isOne();
        service.finishTv("tv-session", id, "STOPPED");
        service.reportTv("tv-session", id, json.valueToTree(Map.of("eventSeq", 3, "state", "PLAYING", "actualPlayedMs", 30_000, "elapsedMs", 30_000)));
        assertThat(mapper.attempt(id).get("state")).isEqualTo("STOPPED");
    }
}
