package com.mynora.singverse.media.missingfeedback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.tv.TvLibrary;
import com.mynora.singverse.tv.TvService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MissingFeedbackService {
    public record Create(String requestId, String title, String artist, String versionLabel,
                         String description, String submittedBy) {}
    public record Update(Long revision, String status, String handlingNote, String resolvedSongId) {}
    public record Feedback(String id, String title, String artist, String versionLabel,
                           String description, String submittedBy, String source, String status,
                           String handlingNote, String resolvedSongId, String resolvedSongTitle,
                           List<String> resolvedSongArtists, boolean resolvedSongAvailable,
                           long revision, String createdAt, String updatedAt, String resolvedAt) {}
    public record Page(List<Feedback> items, long totalElements, int page, int size) {}

    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "RESOLVED", "CLOSED");
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final MissingFeedbackMapper mapper;
    private final TvService songs;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MissingFeedbackService(MissingFeedbackMapper mapper, TvService songs, ObjectMapper json,
                                  PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.songs = songs;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    public Page list(String q, String status, int page, int size) {
        if (q == null || q.length() > 200 || q.chars().anyMatch(Character::isISOControl)
                || status == null || !(status.equals("ALL") || STATUSES.contains(status))
                || page < 0 || page > 1_000_000 || size < 1 || size > 100)
            throw fail(400, "筛选条件或分页无效");
        String needle = TvLibrary.normalize(q.trim());
        String query = needle.isEmpty() ? "" : "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var rows = mapper.list(query, status, (long) page * size, size);
        var available = available(rows);
        return new Page(rows.stream().map(row -> view(row, available)).toList(), mapper.count(query, status), page, size);
    }

    public Feedback get(String id) {
        var row = required(id);
        return view(row, available(List.of(row)));
    }

    public Feedback create(Create input) {
        if (input == null) throw fail(400, "请填写缺歌信息");
        String id;
        try {
            id = UUID.fromString(input.requestId()).toString();
            if (!id.equals(input.requestId())) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw fail(400, "登记请求标识无效"); }
        String title = text(input.title(), 200, false, true);
        String artist = text(input.artist(), 200, false, false);
        String version = text(input.versionLabel(), 120, false, false);
        String description = text(input.description(), 2000, true, false);
        String submittedBy = text(input.submittedBy(), 80, false, false);
        String hash = digest(encode(List.of(title, artist, version, description, submittedBy)));
        return tx.execute(ignored -> {
            mapper.lock();
            var previous = mapper.find(id);
            if (previous != null) {
                if (!hash.equals(previous.get("creation_hash"))) throw fail(409, "这个登记请求已用于其他缺歌信息，请重新登记");
                return view(previous, available(List.of(previous)));
            }
            var row = new HashMap<String, Object>();
            row.put("id", id); row.put("title", title); row.put("artist", artist);
            row.put("version_label", version); row.put("description", description); row.put("submitted_by", submittedBy);
            row.put("search_text", TvLibrary.searchable(title + " " + artist + " " + version));
            row.put("creation_hash", hash); row.put("now", TIMESTAMP.format(Instant.now()));
            mapper.insert(row);
            return get(id);
        });
    }

    public Feedback update(String id, Update input) {
        if (input == null || input.revision() == null || input.revision() < 1
                || input.status() == null || !STATUSES.contains(input.status()))
            throw fail(400, "处理状态或修订号无效");
        String note = text(input.handlingNote(), 2000, true, false);
        String songId = input.resolvedSongId();
        if (songId != null && (songId.isBlank() || songId.length() > 64 || songId.chars().anyMatch(Character::isISOControl)))
            throw fail(400, "请选择有效的关联歌曲");
        if (input.status().equals("RESOLVED") && songId == null)
            throw fail(400, "标记已补齐前，请关联曲库中的歌曲");
        if (!input.status().equals("RESOLVED") && songId != null)
            throw fail(400, "仅已补齐的反馈可以关联歌曲");
        return tx.execute(ignored -> {
            mapper.lock();
            var before = required(id);
            if (((Number) before.get("revision")).longValue() != input.revision())
                throw fail(409, "反馈已被更新，请重新打开后处理");
            ObjectNode song = null;
            if (songId != null) {
                try { song = songs.songDetail(songId); }
                catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() != 404) throw e;
                    throw fail(409, "关联歌曲不存在或已下架，请重新选择");
                }
            }
            String now = TIMESTAMP.format(Instant.now());
            var row = new HashMap<String, Object>();
            row.put("id", id); row.put("revision", input.revision()); row.put("status", input.status());
            row.put("handling_note", note); row.put("resolved_song_id", songId);
            row.put("resolved_song_title", song == null ? null : song.path("title").asText());
            row.put("resolved_song_artists_json", song == null ? "[]" : song.path("artists").toString());
            row.put("resolved_at", song == null ? null : Objects.equals(songId, before.get("resolved_song_id"))
                    && "RESOLVED".equals(before.get("status")) ? before.get("resolved_at") : now);
            row.put("now", now);
            if (mapper.update(row) != 1) throw fail(409, "反馈已被更新，请重新打开后处理");
            return get(id);
        });
    }

    private Map<String, Object> required(String id) {
        var row = mapper.find(id);
        if (row == null) throw fail(404, "缺歌反馈不存在");
        return row;
    }

    private Set<String> available(List<Map<String, Object>> rows) {
        var ids = rows.stream().map(row -> row.get("resolved_song_id")).filter(Objects::nonNull).map(Object::toString).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(mapper.currentSongIds(ids));
    }

    private Feedback view(Map<String, Object> row, Set<String> available) {
        String songId = nullable(row, "resolved_song_id");
        List<String> artists;
        try { artists = json.readValue(row.get("resolved_song_artists_json").toString(), new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("缺歌反馈关联信息无法读取", e); }
        return new Feedback(value(row, "id"), value(row, "title"), value(row, "artist"), value(row, "version_label"),
                value(row, "description"), value(row, "submitted_by"), value(row, "source"), value(row, "status"),
                value(row, "handling_note"), songId, nullable(row, "resolved_song_title"), artists,
                songId != null && available.contains(songId), ((Number) row.get("revision")).longValue(),
                value(row, "created_at"), value(row, "updated_at"), nullable(row, "resolved_at"));
    }

    private String encode(Object input) {
        try { return json.writeValueAsString(input); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(String input, int max, boolean multiline, boolean required) {
        if (input == null || input.length() > max || input.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))))
            throw fail(400, "缺歌信息包含无效内容或超过长度限制");
        String value = input.strip();
        if (required && TvLibrary.normalize(value).isEmpty()) throw fail(400, "请填写歌曲名称");
        return value;
    }

    private static String value(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    private static String nullable(Map<String, Object> row, String key) { return row.get(key) == null ? null : row.get(key).toString(); }
    private static ResponseStatusException fail(int status, String detail) { return new ResponseStatusException(HttpStatusCode.valueOf(status), detail); }
}
