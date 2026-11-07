package com.mynora.singverse.media.playbackhistory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Real playback clocks, distinct from media position, download hits and speculative prefetch. */
@Service
public class PlaybackActivityService {
    public static final long LEASE_MS = 120_000;
    private static final long MAX_DURATION_MS = 86_400_000;
    private static final long MAX_CLOCK_MS = Duration.ofDays(30).toMillis();
    private static final long CLOCK_SKEW_MS = 5000;
    private static final double HALF_LIFE_MS = Duration.ofDays(14).toMillis();
    private static final Set<String> ACTIVE = Set.of("PREPARING", "BUFFERING", "PLAYING", "PAUSED");
    private static final Set<String> STATES = Set.of("PREPARING", "BUFFERING", "PLAYING", "PAUSED",
            "COMPLETED", "STOPPED", "SKIPPED", "ERROR");
    private final PlaybackActivityMapper mapper;
    private final MediaSongRepository songs;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean mysql;
    @Value("${singverse.playback-activity.worker-enabled:true}") private boolean workerEnabled = true;

    @Autowired
    public PlaybackActivityService(PlaybackActivityMapper mapper, MediaSongRepository songs, ObjectMapper json,
            PlatformTransactionManager transactionManager, Environment environment) {
        this(mapper, songs, json, transactionManager, Clock.systemUTC(), environment.acceptsProfiles(Profiles.of("mysql")));
    }

    PlaybackActivityService(PlaybackActivityMapper mapper, MediaSongRepository songs, ObjectMapper json,
            PlatformTransactionManager transactionManager, Clock clock) {
        this(mapper, songs, json, transactionManager, clock, false);
    }

    PlaybackActivityService(PlaybackActivityMapper mapper, MediaSongRepository songs, ObjectMapper json,
            PlatformTransactionManager transactionManager, Clock clock, boolean mysql) {
        this.mapper = mapper;
        this.songs = songs;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.mysql = mysql;
    }

    public Map<String, Object> create(String type, String owner, String song, String device) {
        return create(type, owner, song, device, null);
    }

    public Map<String, Object> create(String type, String owner, String song, String device, Long startedAt) {
        if (!Set.of("ADMIN", "MOBILE").contains(type)) throw failure(400, "播放终端无效");
        validateText(song, 64, false);
        validateText(owner, 64, false);
        validateText(device, 200, true);
        long now = clock.millis(), started = startedAt == null ? now : startedAt;
        if (started < now - MAX_DURATION_MS || started > now + CLOCK_SKEW_MS)
            throw failure(400, "播放开始时间无效");
        String token = AuthCrypto.token(), id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> start(id, type, owner, AuthCrypto.hash(token), song, device, "", started));
        return Map.of("attemptId", id, "writeToken", token, "eventSeq", -1, "leaseSeconds", 120,
                "startedAt", started, "serverTime", now);
    }

    /** Called in the TV aggregate transaction when a new server-owned attempt is confirmed. */
    public void startTv(String id, String session, String song, String device, String requestedBy) {
        transactions.executeWithoutResult(status -> start(id, "TV", session, null, song, device, requestedBy, null));
    }

    private void start(String id, String type, String owner, String tokenHash, String songId,
            String device, String requestedBy, Long startedAt) {
        lockSong(songId);
        var song = songs.get(songId);
        var metadata = song.metadata() == null || song.metadata().stale() ? null : song.metadata();
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("title", metadata != null && metadata.fields().contains("title")
                ? metadata.track().title() : song.result().title());
        snapshot.put("artists_json", encode(metadata != null && metadata.fields().contains("artists")
                ? metadata.track().artists() : song.result().artists()));
        snapshot.put("source_name", song.sourceName());
        snapshot.put("version_label", song.result().versionType().name());
        snapshot.put("device_name", device == null ? "" : device);
        snapshot.put("requested_by", requestedBy == null ? "" : requestedBy);
        long now = clock.millis();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("client_type", type);
        row.put("owner_session_id", owner);
        row.put("token_hash", tokenHash);
        row.put("song_id", songId);
        row.put("snapshot_json", encode(snapshot));
        row.put("started_at", startedAt == null ? now : startedAt);
        row.put("client_started_at", startedAt);
        row.put("last_event_at", now);
        row.put("lease_until", now + LEASE_MS);
        mapper.insertAttempt(row);
    }

    public Map<String, Object> report(String type, String owner, String id, JsonNode event) {
        return transactions.execute(status -> {
            var row = requireAttempt(id);
            if (!type.equals(row.get("client_type")) || !owner.equals(row.get("owner_session_id")))
                throw failure(403, "播放实例不属于当前登录会话");
            String token = event.path("writeToken").asText("");
            if (token.length() != 43 || !MessageDigest.isEqual(AuthCrypto.hash(token).getBytes(StandardCharsets.UTF_8),
                    row.get("token_hash").toString().getBytes(StandardCharsets.UTF_8)))
                throw failure(403, "播放写入凭据无效");
            lockSong(row.get("song_id").toString());
            return apply(mapper.currentAttempt(id, mysql), event, false);
        });
    }

    /** TV identity, session and current attempt are checked by TvService before entering here. */
    public void reportTv(String session, String id, JsonNode event) {
        transactions.executeWithoutResult(status -> {
            var row = requireAttempt(id);
            if (!"TV".equals(row.get("client_type")) || !session.equals(row.get("owner_session_id")))
                throw failure(409, "旧播放实例已失效");
            lockSong(row.get("song_id").toString());
            row = mapper.currentAttempt(id, mysql);
            // STOP/NEXT can end the lease before a delayed device acknowledgment arrives.
            boolean interrupted = "INTERRUPTED".equals(row.get("state"));
            boolean expired = (ACTIVE.contains(row.get("state")) || "ERROR".equals(row.get("state")))
                    && number(row, "lease_until") <= clock.millis();
            if (interrupted || expired) {
                if (Set.of("ENDED", "IDLE").contains(event.path("state").asText())) {
                    counter(event, "eventSeq");
                    // Playback telemetry has ended, but this current TV attempt must still release the queue.
                    if (!interrupted) finish(row, "INTERRUPTED", number(row, "last_event_at"));
                    return;
                }
                throw failure(410, "播放报告租约已到期，请重新开始");
            }
            if (ACTIVE.contains(row.get("state")) || "ERROR".equals(row.get("state"))) apply(row, event, true);
        });
    }

    private Map<String, Object> apply(Map<String, Object> row, JsonNode event, boolean tv) {
        long seq = counter(event, "eventSeq");
        if ("INTERRUPTED".equals(row.get("state"))) throw failure(410, "播放报告租约已到期，请重新开始");
        boolean recoveringError = "ERROR".equals(row.get("state"));
        long now = clock.millis();
        if ((ACTIVE.contains(row.get("state")) || recoveringError) && number(row, "lease_until") <= now)
            throw failure(410, "播放报告租约已到期，请重新开始");
        if (seq <= number(row, "event_seq")) return view(row);
        if (!ACTIVE.contains(row.get("state")) && !recoveringError)
            throw failure(409, "播放实例已结束，请重新开始");
        String state = event.path("state").asText("");
        if (tv) state = switch (state) { case "ENDED" -> "COMPLETED"; case "IDLE" -> "STOPPED"; default -> state; };
        if (!STATES.contains(state)) throw failure(400, "播放状态无效");
        boolean clockProvided = event.has("actualPlayedMs") || event.has("elapsedMs");
        if (!tv && !clockProvided) throw failure(400, "缺少真实播放时钟");
        long actual = clockProvided ? counter(event, "actualPlayedMs") : number(row, "actual_played_ms");
        long elapsed = clockProvided ? counter(event, "elapsedMs") : number(row, "elapsed_ms");
        long priorActual = number(row, "actual_played_ms"), priorElapsed = number(row, "elapsed_ms");
        long origin = row.get("client_started_at") == null ? number(row, "started_at") : number(row, "client_started_at");
        if (tv && event.has("startedAt")) {
            long clientStarted = counter(event, "startedAt");
            if (row.get("client_started_at") != null && clientStarted != origin
                    || clientStarted < number(row, "started_at") - CLOCK_SKEW_MS || clientStarted > now + CLOCK_SKEW_MS)
                throw failure(400, "播放开始时钟不能改变");
            origin = clientStarted;
            row.put("client_started_at", clientStarted);
        }
        long occurred = event.has("occurredAt") ? counter(event, "occurredAt")
                : clockProvided ? origin + elapsed : now;
        if (occurred > now + CLOCK_SKEW_MS || occurred < origin - CLOCK_SKEW_MS
                || number(row, "event_seq") >= 0 && occurred < number(row, "last_event_at")
                || clockProvided && Math.abs(occurred - origin - elapsed) > CLOCK_SKEW_MS)
            throw failure(400, "播放事件发生时间无效");
        if (actual < priorActual || elapsed < priorElapsed || actual > MAX_CLOCK_MS || elapsed > MAX_CLOCK_MS
                || actual > elapsed + 1000 || actual - priorActual > elapsed - priorElapsed + 1000
                || elapsed > Math.max(0, now - origin) + CLOCK_SKEW_MS)
            throw failure(400, "真实播放时钟无效，定位跳转不能计入播放时长");
        boolean first = row.get("started_playing_at") == null && (state.equals("PLAYING") || actual > 0);
        boolean resumed = state.equals("PLAYING") && !"PLAYING".equals(row.get("state"));
        boolean activity = first || actual > priorActual || resumed;
        Long previousActivity = nullableNumber(row, "last_activity_at");
        Long priorClientActivity = nullableNumber(row, "client_last_activity_at");
        if (priorClientActivity == null) priorClientActivity = previousActivity;
        Long clientActivity = priorClientActivity;
        if (event.hasNonNull("lastActivityAt")) clientActivity = counter(event, "lastActivityAt");
        else if (activity) clientActivity = occurred;
        if (clientActivity != null && (clientActivity > occurred + CLOCK_SKEW_MS || clientActivity < origin - CLOCK_SKEW_MS
                || priorClientActivity != null && clientActivity < priorClientActivity
                || !activity && (priorClientActivity == null || clientActivity > priorClientActivity)))
            throw failure(400, "实际播放发生时间无效");
        long happened = Math.min(now, occurred);
        Long lastActivity = previousActivity;
        if (activity && clientActivity != null) lastActivity = Math.min(happened, clientActivity);
        Long duration = row.get("duration_ms") == null ? null : number(row, "duration_ms");
        if (event.has("durationMs")) {
            duration = counter(event, "durationMs");
            if (duration <= 0 || duration > MAX_DURATION_MS) throw failure(400, "播放总时长无效");
        }
        row.put("duration_ms", duration);
        row.put("event_seq", seq);
        row.put("actual_played_ms", actual);
        row.put("elapsed_ms", elapsed);
        row.put("state", state);
        row.put("last_event_at", happened);
        row.put("lease_until", ACTIVE.contains(state) || state.equals("ERROR") ? happened + LEASE_MS : happened);
        if (first) {
            row.put("started_playing_at", Math.min(now, Math.max(origin, lastActivity - actual)));
            row.put("history_id", UUID.randomUUID().toString());
            row.putAll(decode(row.get("snapshot_json").toString()));
            mapper.insertHistory(row);
        }
        if (activity && lastActivity != null) {
            row.put("last_activity_at", lastActivity);
            row.put("client_last_activity_at", clientActivity);
            touchActivity(row.get("song_id").toString(), lastActivity);
        }
        if (row.get("history_id") != null) {
            if (row.get("effective_at") == null && !"ADMIN".equals(row.get("client_type"))
                    && (actual >= 30_000 || duration != null && duration < 30_000
                        && state.equals("COMPLETED") && actual >= duration)) row.put("effective_at", lastActivity);
            row.put("history_status", ACTIVE.contains(state) ? "PLAYING" : state);
            row.put("ended_at", ACTIVE.contains(state) ? null : happened);
            mapper.updateHistory(row);
            // During initial backfill new events join the same ordered, durable pending queue.
            if (isReady()) aggregate(mapper.currentHistory(row.get("history_id").toString(), mysql));
        }
        mapper.updateAttempt(row);
        return view(row);
    }

    private void touchActivity(String song, long now) {
        var p = mapper.currentProfile(song, mysql);
        Long last = p.get("last_played_at") == null ? now : Math.max(now, number(p, "last_played_at"));
        mapper.updateProfile(song, last, decimal(p, "heat"), nullableNumber(p, "heat_updated_at"));
    }

    /** Commands ending/replacing a TV attempt stop its cache lease without inventing listening time. */
    public void finishTv(String session, String id, String state) {
        if (id == null || id.isBlank()) return;
        transactions.executeWithoutResult(status -> {
            var row = mapper.attempt(id);
            if (row == null || !"TV".equals(row.get("client_type"))
                    || !session.equals(row.get("owner_session_id"))) return;
            lockSong(row.get("song_id").toString());
            row = mapper.currentAttempt(id, mysql);
            if (ACTIVE.contains(row.get("state")) || "ERROR".equals(row.get("state"))) finish(row, state, clock.millis());
        });
    }

    public boolean isReady() {
        return number(mapper.aggregationState(), "ready") == 1;
    }

    /** At most 200 records per tick. Each aggregation locks one song in its own transaction. */
    public int backfillBatch() {
        if ("PREPARE".equals(mapper.aggregationState().get("phase"))) {
            return transactions.execute(status -> {
                var unprepared = mapper.unpreparedHistory(200);
                long started = 0;
                String id = "";
                for (var row : unprepared) {
                    mapper.prepareHistory(row.get("id").toString(), effectiveTime(row));
                    started = number(row, "started_at");
                    id = row.get("id").toString();
                }
                mapper.checkpoint(0, mapper.unpreparedHistory(1).isEmpty() ? "AGGREGATE" : "PREPARE", started, id);
                return unprepared.size();
            });
        }
        var rows = mapper.pendingHistory(200);
        for (var row : rows) transactions.executeWithoutResult(status -> {
            lockSong(row.get("song_id").toString());
            var current = mapper.currentHistory(row.get("id").toString(), mysql);
            // A live report can qualify while waiting for its song lock. Re-select it in the correct order.
            if (!Objects.equals(effectiveTime(row), effectiveTime(current))) return;
            aggregate(current);
            mapper.checkpoint(0, "AGGREGATE", number(row, "started_at"), row.get("id").toString());
        });
        transactions.executeWithoutResult(status -> {
            if (!rows.isEmpty() || !isReady()) {
                boolean ready = mapper.pendingHistory(1).isEmpty();
                var checkpoint = mapper.aggregationState();
                mapper.checkpoint(ready ? 1 : 0, ready ? "READY" : "AGGREGATE",
                        number(checkpoint, "cursor_started_at"), checkpoint.get("cursor_id").toString());
            }
        });
        return rows.size();
    }

    @Scheduled(scheduler = "transcodeScheduler", fixedDelay = 1000, initialDelay = 5000)
    public void scheduledMaintain() { if (workerEnabled) maintain(); }

    public void maintain() {
        try {
            backfillBatch();
            for (var expired : mapper.expired(clock.millis(), 200)) transactions.executeWithoutResult(status -> {
                lockSong(expired.get("song_id").toString());
                var current = mapper.currentAttempt(expired.get("id").toString(), mysql);
                if ((ACTIVE.contains(current.get("state")) || "ERROR".equals(current.get("state")))
                        && number(current, "lease_until") <= clock.millis())
                    finish(current, "INTERRUPTED", number(current, "last_event_at"));
            });
        } catch (org.springframework.dao.TransientDataAccessException conflict) {
            // A contended/temporarily unavailable database is retried on the next bounded tick.
        }
    }

    private void finish(Map<String, Object> row, String state, long endedAt) {
        row.put("state", state);
        row.put("lease_until", clock.millis());
        row.put("history_status", state);
        row.put("ended_at", Math.max(endedAt, number(row, "started_playing_at")));
        if (row.get("history_id") != null) mapper.updateHistory(row);
        mapper.updateAttempt(row);
    }

    private void aggregate(Map<String, Object> row) {
        if (row == null || number(row, "heat_applied") == 1) return;
        String song = row.get("song_id").toString();
        var p = mapper.currentProfile(song, mysql);
        long started = number(row, "started_at"), played = number(row, "played_ms");
        Long effective = effectiveTime(row);
        boolean eligible = !"ADMIN".equals(row.get("client_type")) && (played >= 30_000
                || row.get("duration_ms") != null && number(row, "duration_ms") > 0
                && number(row, "duration_ms") < 30_000 && "COMPLETED".equals(row.get("status"))
                && played >= number(row, "duration_ms"));
        Long last = nullableNumber(p, "last_played_at");
        // Legacy records have no actual-activity clock. Preserve their documented start-time convention.
        long activity = row.get("last_activity_at") != null ? number(row, "last_activity_at")
                : started;
        activity = Math.min(clock.millis(), Math.max(started, activity));
        last = last == null ? activity : Math.max(last, activity);
        double heat = decimal(p, "heat");
        Long heatAt = nullableNumber(p, "heat_updated_at");
        if (eligible && effective != null) {
            if (heatAt != null && effective < heatAt) {
                // Capping is order-dependent. Replaying only this song's committed increments is exact,
                // while excluding other pending rows prevents them being counted again on their own turn.
                heat = 0;
                heatAt = null;
                for (long at : mapper.appliedHeatTimes(song, row.get("id").toString(), mysql)) {
                    heat = Math.min(1, heat * decay(heatAt == null ? 0 : at - heatAt) + .05);
                    heatAt = at;
                }
            } else {
                heat = Math.min(1.0, heat * decay(heatAt == null ? 0 : effective - heatAt) + 0.05);
                heatAt = effective;
            }
        }
        mapper.updateProfile(song, last, heat, heatAt);
        mapper.markAggregated(row.get("id").toString(), effective);
    }

    static double decay(long ageMs) { return Math.pow(2, -Math.max(0, ageMs) / HALF_LIFE_MS); }

    private Long effectiveTime(Map<String, Object> row) {
        if (row.get("effective_at") != null) return number(row, "effective_at");
        long played = number(row, "played_ms");
        if ("ADMIN".equals(row.get("client_type")) || !(played >= 30_000
                || row.get("duration_ms") != null && number(row, "duration_ms") > 0
                && number(row, "duration_ms") < 30_000 && "COMPLETED".equals(row.get("status"))
                && played >= number(row, "duration_ms"))) return null;
        return Math.min(clock.millis(), number(row, "started_at"));
    }

    private void lockSong(String song) {
        if (mapper.lockProfile(song) != 0) return;
        try { mapper.ensureProfile(song); }
        catch (DuplicateKeyException concurrentlyCreated) { /* The winning insert committed its song lock row. */ }
        mapper.lockProfile(song);
    }

    private Map<String, Object> requireAttempt(String id) {
        validateText(id, 36, false);
        var row = mapper.attempt(id);
        if (row == null) throw failure(404, "播放实例不存在");
        return row;
    }

    private Map<String, Object> view(Map<String, Object> row) {
        return Map.of("attemptId", row.get("id"), "eventSeq", number(row, "event_seq"),
                "state", row.get("state"), "effective", row.get("effective_at") != null, "leaseSeconds", 120);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("无法保存播放快照", error); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("播放快照无效", error); }
    }

    private static void validateText(String value, int max, boolean empty) {
        if (value == null || !empty && value.isBlank() || value != null && (value.length() > max
                || value.chars().anyMatch(Character::isISOControl))) throw failure(400, "播放字段无效");
    }

    private static long counter(JsonNode event, String name) {
        var n = event.path(name);
        if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < 0)
            throw failure(400, "播放字段无效：" + name);
        return n.asLong();
    }

    private static long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.longValue() : 0;
    }
    private static Long nullableNumber(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : number(row, key);
    }
    private static double decimal(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.doubleValue() : 0;
    }
    private static ResponseStatusException failure(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
}
