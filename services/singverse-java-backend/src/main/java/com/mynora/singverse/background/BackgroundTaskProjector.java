package com.mynora.singverse.background;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A display projection, never a task executor. The mapper interceptor supplies the SAME
 * MyBatis executor so source changes and their snapshots commit or roll back together.
 */
@Component
public class BackgroundTaskProjector {
    static final String NS = "com.mynora.singverse.background.BackgroundTaskMapper.";
    static final long RETENTION = 30L * 86400_000;
    static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "PAUSED", "CANCELLING", "AWAITING_CONFIRMATION");
    static final List<String> TYPES = List.of("WEBDAV_SCAN", "METADATA", "CLASSIFICATION",
            "AUDIO_BATCH", "AUDIO_JOB", "TRANSCODE", "CACHE_CLEANUP", "SHARE_EXPORT", "LX_DOWNLOAD");
    static final Set<String> SOURCE_ACTIVE = Set.of("QUEUED", "RUNNING", "PAUSED", "STARTING",
            "PREPARING", "PREVIEW", "WAITING", "CANCELLING");

    interface Access {
        List<Map<String, Object>> query(String statement, Map<String, Object> args);
        int write(String statement, Map<String, Object> args);
    }

    private final ObjectMapper json;
    final BackgroundTaskBatches batches;
    private final Map<String, Long> captured = new ConcurrentHashMap<>();
    record Pending(String type, String businessId) {}
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    List<Pending> drain() {
        var batch = new ArrayList<Pending>();
        pending.forEach((id, value) -> { if (pending.remove(id, value)) batch.add(value); });
        return batch;
    }

    public BackgroundTaskProjector(ObjectMapper json) { this(json, new BackgroundTaskBatches(json)); }
    @org.springframework.beans.factory.annotation.Autowired
    public BackgroundTaskProjector(ObjectMapper json, BackgroundTaskBatches batches) { this.json = json; this.batches = batches; }

    static String id(String type, String businessId) {
        return UUID.nameUUIDFromBytes((type + "\0" + businessId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    static long number(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.longValue() : 0;
    }
    static Long time(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value == null || value.toString().isBlank()) return null;
        try { return Instant.parse(value.toString()).toEpochMilli(); }
        catch (RuntimeException ignored) { return null; }
    }
    static Map<String, Object> args(Object... pairs) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i + 1]);
        return result;
    }
    static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }
    static String limit(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }

    /** Keep useful business explanations, strip network addresses, filesystem paths and credential-like values. */
    static String message(String value) {
        return limit(value.replaceAll("(?i)\\b(?:https?|file|smb)://[^\\s<>\"']+", "[地址已隐藏]")
                .replaceAll("(?i)\\b(?:authorization\\s*[=:]\\s*)?bearer\\s+[^\\s,;\"'}]+", "[凭据已隐藏]")
                .replaceAll("(?i)(?:password|secret|token|api[_-]?key|authorization)[\"']?\\s*[=:]\\s*[\"']?[^\\s,;\"'}]+", "[凭据已隐藏]")
                .replaceAll("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?\\b", "[地址已隐藏]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[凭据已隐藏]")
                .replaceAll("(?<![\\w])(?:[A-Za-z]:[\\\\/]|/)(?:[^\\s/\\\\:]+[/\\\\])+[^\\s,;\"']*", "[路径已隐藏]"), 1000);
    }
    JsonNode read(Object value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value.toString()); }
        catch (Exception ignored) { return json.createObjectNode(); }
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("任务展示结果编码失败", error); }
    }
    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Only the documented, bounded execution diagnostics enter the display projection. */
    ObjectNode execution(JsonNode source) {
        var out = json.createObjectNode();
        for (String field : List.of("stage", "backend", "modelVersion", "fallbackReason"))
            if (source.path(field).isTextual()) out.put(field, message(limit(source.path(field).asText(), 200)));
        var progress = source.path("progress");
        if (progress.path("frames").canConvertToLong() && progress.path("frames").asLong() > 0
                && progress.path("completedFrames").canConvertToLong()) {
            out.putObject("progress").put("frames", progress.path("frames").asLong())
                    .put("completedFrames", Math.max(0, Math.min(progress.path("frames").asLong(), progress.path("completedFrames").asLong())));
        }
        var timings = source.path("timingsMs");
        for (String name : List.of("queue", "download", "probe", "decode", "inference", "encode", "total"))
            if (timings.path(name).canConvertToLong() && timings.path(name).asLong() >= 0)
                out.withObject("/timingsMs").put(name, timings.path(name).asLong());
        return out;
    }

    void refresh(Access db, String type, String businessId, boolean historical, boolean force) {
        if (businessId == null || businessId.isBlank()) return;
        String taskId = id(type, businessId);
        var key = args("type", type, "businessId", businessId, "taskId", taskId);
        var previous = first(db.query("find", args("id", taskId)));
        var source = first(db.query("source", key));
        long now = System.currentTimeMillis();
        if (source.isEmpty()) {
            if (!previous.isEmpty() && ACTIVE.contains(text(previous, "status"))) {
                var expired = new HashMap<>(previous);
                expired.put("status", "EXPIRED"); expired.put("business_status", "EXPIRED");
                expired.put("stage", "EXPIRED"); expired.put("error", "原业务任务记录已过期或移除");
                expired.put("updated_at", now); expired.put("finished_at", now); expired.put("expires_at", now + RETENTION);
                expired.put("fingerprint", hash(List.of(taskId, "EXPIRED")));
                db.write("update", expired); db.write("sealScope", args("id", taskId));
                batches.changed(db, taskId); captured.remove(taskId);
            }
            return;
        }
        if (type.equals("AUDIO_JOB") && number(source, "tracked") == 0) return;
        String raw = text(source, "raw_status");
        Long created = time(source.get("created_at")), updated = time(source.get("updated_at"));
        Long sourceFinished = time(source.get("finished_at"));
        long anchor = sourceFinished != null ? sourceFinished : updated != null ? updated : created != null ? created : now;
        if (previous.isEmpty() && !SOURCE_ACTIVE.contains(raw) && anchor < now - RETENTION) return;
        if (!force && !previous.isEmpty() && SOURCE_ACTIVE.contains(raw)
                && raw.equals(text(previous, "business_status"))
                && now - captured.getOrDefault(taskId, 0L) < 1000) {
            pending.put(taskId, new Pending(type, businessId));
            return;
        }
        pending.remove(taskId);
        captured.put(taskId, now);

        var row = new TreeMap<String, Object>();
        row.put("id", taskId); row.put("type", type); row.put("business_id", businessId);
        String parent = text(source, "parent_business_id");
        row.put("parent_id", !previous.isEmpty() ? previous.get("parent_id") : parent.isBlank() ? null : id("AUDIO_BATCH", parent));
        row.put("source_id", source.get("source_id")); row.put("song_id", source.get("song_id"));
        String subject = text(source, "subject_name");
        if (subject.isBlank() && source.containsKey("song_json")) subject = read(source.get("song_json")).path("title").asText("");
        if (subject.isBlank()) subject = text(previous, "subject_name");
        row.put("subject_name", limit(subject, 512));
        row.put("business_status", raw); row.put("status", normalized(raw)); row.put("stage", raw);
        row.put("completed", null); row.put("total", null); row.put("progress_unit", "ITEMS");
        row.put("error", message(text(source, "error")));
        row.put("items_available", 0);
        var statistics = json.createObjectNode();
        var diagnostics = json.createObjectNode();
        var counts = new TreeMap<String, Long>();
        long executing = 0;
        for (var count : db.query("sourceCounts", key)) {
            counts.put(text(count, "status"), number(count, "amount"));
            executing += number(count, "executing");
        }
        if (!counts.isEmpty()) {
            counts.forEach((status, count) -> statistics.withObject("/counts").put(status, count));
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            long pending = counts.getOrDefault("PENDING", 0L) + counts.getOrDefault("QUEUED", 0L);
            long running = counts.getOrDefault("PROCESSING", 0L) + counts.getOrDefault("PROBING", 0L)
                    + counts.getOrDefault("RESOLVING",0L) + counts.getOrDefault("DOWNLOADING",0L) + counts.getOrDefault("PUBLISHING",0L);
            long skipped = counts.getOrDefault("SKIPPED_EXISTING", 0L) + counts.getOrDefault("SKIPPED", 0L);
            long legacy = type.equals("METADATA") ? Math.max(0, number(source, "skipped_existing") - skipped) : 0;
            total += legacy;
            row.put("total", total); row.put("completed", total - pending - running); row.put("progress_unit", "SONGS");
            if (type.equals("AUDIO_BATCH")) { pending += running - executing; running = executing; }
            statistics.put("pending", pending).put("processing", running).put("review", counts.getOrDefault("REVIEW", 0L))
                    .put("failed", counts.getOrDefault("FAILED", 0L)).put("skipped", skipped + legacy)
                    .put("applied", counts.getOrDefault("AUTO_APPLIED", 0L) + counts.getOrDefault("MANUAL_APPLIED", 0L)
                            + counts.getOrDefault("APPLIED", 0L));
            row.put("items_available", 1);
            if (raw.equals("COMPLETED") && counts.getOrDefault("FAILED", 0L) > 0) row.put("status", "PARTIAL");
            if (type.equals("AUDIO_BATCH") && raw.equals("QUEUED") && running > 0) row.put("status", "RUNNING");
        }
        String label;
        switch (type) {
            case "LX_DOWNLOAD" -> {
                label="歌曲下载";row.put("stage",raw.equals("RUNNING")?"DOWNLOADING":raw);
                var nativeCounts=statistics.path("counts");statistics.put("applied",nativeCounts.path("SUCCESS").asLong())
                        .put("interrupted",nativeCounts.path("INTERRUPTED").asLong()).put("cancelled",nativeCounts.path("CANCELLED").asLong());
            }
            case "WEBDAV_SCAN" -> {
                label = "WebDAV 扫描";
                row.put("stage", text(source, "stage"));
                if (number(source, "cancel_requested") != 0 && SOURCE_ACTIVE.contains(raw)) row.put("status", "CANCELLING");
                for (String field : List.of("directories", "entries", "total_songs", "matched_songs", "review_songs",
                        "skipped_files", "added_songs", "reused_songs", "missing_songs", "removed_songs"))
                    statistics.put(field, number(source, field));
            }
            case "METADATA" -> { label = "元数据整理"; row.put("stage", raw.equals("RUNNING") ? "MATCHING" : raw); }
            case "CLASSIFICATION" -> { label = "AI 分类"; row.put("stage", raw.equals("RUNNING") ? "CLASSIFYING" : raw); }
            case "AUDIO_BATCH" -> {
                label = "批量伴奏生成";
                row.put("total", Math.max(number(source, "selection_total"), row.get("total") instanceof Number n ? n.longValue() : 0));
                row.put("progress_unit", "SONGS");
                statistics.put("prepared", number(source, "prepared_count"));
                if (raw.equals("PREPARING")) row.put("completed", number(source, "prepared_count"));
                if (raw.equals("PREVIEW")) row.put("completed", 0L);
                row.put("stage", raw.equals("QUEUED") && text(row, "status").equals("RUNNING") ? "PROCESSING" : raw);
                row.put("items_available", 1);
            }
            case "AUDIO_JOB" -> {
                label = switch (text(source, "kind")) {
                    case "SEPARATE" -> "伴奏生成"; case "ANALYZE" -> "音轨深度识别";
                    case "FAST" -> "音轨快速识别"; default -> "音轨探测";
                };
                diagnostics = execution(read(source.get("result_json")));
                row.put("stage", diagnostics.path("stage").asText(raw));
                var result = read(source.get("result_json"));
                if (result.path("reason").isTextual()) diagnostics.put("reason", message(result.path("reason").asText()));
                if (result.path("state").isTextual()) diagnostics.put("resultState", limit(result.path("state").asText(), 50));
            }
            case "TRANSCODE" -> {
                label = "离线转码"; diagnostics = execution(read(source.get("result_json")));
                row.put("stage", diagnostics.path("stage").asText(raw));
            }
            case "CACHE_CLEANUP" -> {
                label = "缓存清理"; row.put("stage", text(source, "mode"));
                var result = read(source.get("result_json"));
                for (String field : List.of("removed", "skipped", "failed", "freedBytes", "usageBeforeBytes",
                        "usageAfterBytes", "targetBytes", "shortfallBytes"))
                    if (result.path(field).canConvertToLong()) statistics.put(field, Math.max(0, result.path(field).asLong()));
                for (String field : List.of("targetReached", "spaceInsufficient"))
                    if (result.path(field).isBoolean()) statistics.put(field, result.path(field).asBoolean());
                if (result.path("skippedByReason").isObject())
                    result.path("skippedByReason").properties().stream().limit(30).forEach(entry -> {
                        if (entry.getValue().canConvertToLong())
                            statistics.withObject("/skippedByReason").put(limit(message(entry.getKey()), 80), entry.getValue().asLong());
                    });
                row.put("completed", result.path("removed").asLong() + result.path("skipped").asLong() + result.path("failed").asLong());
                if (result.path("errors").isArray()) {
                    int index = 0;
                    for (var error : result.path("errors")) {
                        if (index >= 20) break;
                        saveItem(db, taskId, args("id", "error-" + index++, "title", error.path("id").asText("缓存项"),
                                "status", "FAILED", "message", error.path("message").asText("清理失败"),
                                "source_updated_at", Objects.toString(source.get("updated_at"), "")));
                    }
                    row.put("items_available", result.path("errors").isEmpty() ? 0 : 1);
                    statistics.put("errorDetailsLimited", result.path("failed").asLong() > result.path("errors").size());
                }
            }
            case "SHARE_EXPORT" -> {
                label = "分享包导出";
                statistics.put("exportedSongs", number(source, "processed_count")).put("snapshotSongs", number(source, "total_count"))
                        .put("parts", number(source, "part_count"));
                row.put("completed", number(source, "processed_count"));
                if (number(source, "incremental") == 0 && !raw.equals("WAITING")) row.put("total", number(source, "total_count"));
                row.put("progress_unit", "SONGS");
            }
            default -> throw new IllegalArgumentException(type);
        }
        row.put("name", limit(label + (subject.isBlank() ? "" : " · " + subject), 512));
        var detail = json.createObjectNode().set("statistics", statistics);
        ((ObjectNode) detail).set("execution", diagnostics);
        row.put("statistics_json", encode(detail));
        row.put("created_at", created != null ? created : number(previous, "created_at"));
        row.put("updated_at", previous.isEmpty() ? (updated != null ? updated : created != null ? created : now) : number(previous, "updated_at"));
        row.put("started_at", source.get("started_at") == null ? previous.get("started_at") : time(source.get("started_at")));
        boolean terminal = !ACTIVE.contains(text(row, "status"));
        boolean wasActive = !previous.isEmpty() && ACTIVE.contains(text(previous, "status"));
        if (!historical && row.get("started_at") == null && text(row, "status").equals("RUNNING"))
            row.put("started_at", now);
        Long finished = sourceFinished != null ? sourceFinished : time(previous.get("finished_at"));
        if (terminal && finished == null && !historical && (previous.isEmpty() || wasActive)) finished = now;
        row.put("finished_at", terminal ? finished : null);
        row.put("expires_at", terminal ? (finished != null ? finished : anchor) + RETENTION : null);
        row.put("time_incomplete", historical && (row.get("started_at") == null || terminal && finished == null)
                || number(previous, "time_incomplete") != 0 ? 1 : 0);
        // Timestamp-independent comparison prevents polling, no-op writes and retries from inventing progress.
        var comparable = new TreeMap<>(row);
        for (String field : List.of("updated_at", "finished_at", "expires_at", "started_at")) comparable.remove(field);
        String fingerprint = hash(comparable);
        row.put("fingerprint", fingerprint);
        if (!fingerprint.equals(text(previous, "fingerprint"))) {
            if (!previous.isEmpty()) row.put("updated_at", now);
            if (previous.isEmpty()) db.write("insertProjected", row);
            db.write("update", row);
        }
        batches.classify(db, taskId, historical || !previous.isEmpty() && text(previous, "batch_kind").equals("UNCLASSIFIED"));
        if (type.equals("WEBDAV_SCAN") && terminal && !raw.equals("SUCCEEDED")) db.write("sealScope", args("id", taskId));
        if (Set.of("METADATA", "CLASSIFICATION", "AUDIO_BATCH", "LX_DOWNLOAD").contains(type)) {
            String cursor = "";
            while (true) {
                key.put("cursor", cursor);
                var items = db.query("sourceItems", key);
                if (items.isEmpty()) break;
                for (var item : items) {
                    saveItem(db, taskId, item);
                    if (type.equals("AUDIO_BATCH") && !text(item, "job_id").isBlank()) {
                        String childId = id("AUDIO_JOB", text(item, "job_id"));
                        refresh(db, "AUDIO_JOB", text(item, "job_id"), historical, true);
                        batches.link(db, taskId, childId, false);
                    }
                }
                cursor = text(items.getLast(), "id");
            }
        }
        batches.changed(db, taskId);
        if (terminal) captured.remove(taskId);
    }

    private void saveItem(Access db, String taskId, Map<String, Object> source) {
        String title = text(source, "title");
        if (title.isBlank()) title = read(source.get("input_json")).path("title").asText("");
        if (title.isBlank()) title = read(source.get("track_json")).path("title").asText("");
        var execution = execution(read(source.get("execution")));
        if (source.get("job_state") != null) execution.put("jobState", limit(text(source, "job_state"), 30));
        var item = args("task_id", taskId, "id", text(source, "id"), "song_id", source.get("song_id"),
                "title", limit(title, 2048), "status", limit(text(source, "status"), 30),
                "message", message(text(source, "message")), "execution_json", encode(execution),
                "source_updated_at", text(source, "source_updated_at"), "execution_updated_at", number(source, "execution_updated_at"));
        if (db.query("findItem", item).isEmpty()) db.write("insertProjectedItem", item);
        db.write("updateItem", item);
    }

    static String normalized(String raw) {
        return switch (raw) {
            case "PREPARING", "STARTING", "RUNNING", "PROCESSING" -> "RUNNING";
            case "PREVIEW" -> "AWAITING_CONFIRMATION";
            case "WAITING", "PENDING", "QUEUED" -> "QUEUED";
            case "SUCCEEDED", "COMPLETED" -> "COMPLETED";
            case "DELETING" -> "CANCELLED";
            case "PAUSED", "CANCELLING", "CANCELLED", "FAILED", "PARTIAL", "INTERRUPTED", "BLOCKED", "EXPIRED" -> raw;
            default -> "FAILED";
        };
    }
}
