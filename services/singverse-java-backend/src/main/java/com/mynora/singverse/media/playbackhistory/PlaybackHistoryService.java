package com.mynora.singverse.media.playbackhistory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Administrator read model. Playback collection is intentionally not wired to any client yet. */
@Service
public class PlaybackHistoryService {
    public record Filter(String pattern, String songId, long from, long until) {}

    private final PlaybackHistoryMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public PlaybackHistoryService(PlaybackHistoryMapper mapper, ObjectMapper json) {
        this(mapper, json, Clock.systemUTC());
    }

    PlaybackHistoryService(PlaybackHistoryMapper mapper, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> history(String q, String period, String songId, int page, int size) {
        validatePage(page, size);
        var filter = filter(q, period, songId);
        var items = mapper.history(filter, (long) page * size, size).stream()
                .map(this::historyView).toList();
        return page(items, mapper.countHistory(filter), page, size);
    }

    public Map<String, Object> popular(String q, String period, String songId, int page, int size) {
        validatePage(page, size);
        var filter = filter(q, period, songId);
        var items = mapper.popular(filter, (long) page * size, size).stream()
                .map(this::popularView).toList();
        return page(items, mapper.countPopular(filter), page, size);
    }

    public Map<String, Object> summary(String q, String period, String songId) {
        var row = mapper.summary(filter(q, period, songId));
        return Map.of(
                "totalPlays", number(row, "total_plays"),
                "effectivePlays", number(row, "effective_plays"),
                "playedSongs", number(row, "played_songs"),
                "playedMs", number(row, "played_ms"));
    }

    private Filter filter(String q, String period, String songId) {
        validateText(q, 200);
        validateText(songId, 64);
        if (period == null || !Set.of("7d", "30d", "all").contains(period))
            throw invalid("请选择近 7 天、近 30 天或全部时间");
        long now = clock.millis();
        long from = period.equals("all") ? Long.MIN_VALUE
                : now - Duration.ofDays(period.equals("7d") ? 7 : 30).toMillis();
        String escaped = q.strip().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return new Filter(escaped.isEmpty() ? "" : "%" + escaped + "%", songId, from, now);
    }

    private static void validateText(String text, int maximum) {
        if (text == null || text.length() > maximum || text.chars().anyMatch(Character::isISOControl))
            throw invalid("关键词或歌曲编号无效");
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100)
            throw invalid("分页参数无效");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static Map<String, Object> page(List<?> items, long total, int page, int size) {
        return Map.of("items", items, "totalElements", total, "page", page, "size", size);
    }

    private Map<String, Object> songView(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("songId", row.get("song_id"));
        view.put("title", row.get("title"));
        try {
            List<String> artists = json.readValue(row.get("artists_json").toString(), new TypeReference<>() {});
            view.put("artists", artists);
        } catch (Exception exception) {
            throw new IllegalStateException("播放记录歌手快照无效", exception);
        }
        view.put("versionLabel", row.get("version_label"));
        view.put("sourceName", row.get("source_name"));
        return view;
    }

    private Map<String, Object> historyView(Map<String, Object> row) {
        var view = songView(row);
        view.put("id", row.get("id"));
        view.put("clientType", row.get("client_type"));
        view.put("deviceName", row.get("device_name"));
        view.put("requestedBy", row.get("requested_by"));
        view.put("startedAt", time(row, "started_at"));
        view.put("endedAt", time(row, "ended_at"));
        view.put("playedMs", number(row, "played_ms"));
        view.put("durationMs", row.get("duration_ms") == null ? null : number(row, "duration_ms"));
        view.put("status", row.get("status"));
        view.put("effective", number(row, "effective_play") == 1);
        return view;
    }

    private Map<String, Object> popularView(Map<String, Object> row) {
        var view = songView(row);
        view.put("playCount", number(row, "play_count"));
        view.put("effectivePlayCount", number(row, "effective_play_count"));
        view.put("completedCount", number(row, "completed_count"));
        view.put("lastPlayedAt", time(row, "last_played_at"));
        return view;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String time(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : Instant.ofEpochMilli(number(row, key)).toString();
    }
}
