package com.mynora.singverse.background;

import static com.mynora.singverse.background.BackgroundTaskProjector.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Batch membership and aggregation use the caller's transaction and MyBatis executor. */
@Component
public class BackgroundTaskBatches {
    private final ObjectMapper json;
    private final ThreadLocal<Set<String>> importing = new ThreadLocal<>();
    Set<String> beginImport() { var changed = new LinkedHashSet<String>(); importing.set(changed); return changed; }
    void endImport() { importing.remove(); }
    private void aggregateOrDefer(Access db, String id) {
        var changed = importing.get();
        if (changed == null) aggregate(db, id); else changed.add(id);
    }
    public BackgroundTaskBatches(ObjectMapper json) { this.json = json; }
    private JsonNode read(Object value) {
        try { return value == null ? json.createObjectNode() : json.readTree(value.toString()); }
        catch (Exception ignored) { return json.createObjectNode(); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static boolean pipeline(Map<String, Object> row) {
        return Set.of("LOCAL_SCAN", "WEBDAV_SCAN", "AUDIO_DISCOVERY").contains(text(row, "type"));
    }
    void classify(Access db, String taskId, boolean historical) {
        var row = first(db.query("find", args("id", taskId)));
        if (row.isEmpty() || !text(row, "batch_kind").equals("UNCLASSIFIED")) return;
        db.write("initializeBatch", args("id", taskId, "kind", "OPERATION", "sealed", pipeline(row) && !historical ? 0 : 1));
        String parent = text(row, "parent_id");
        if (!parent.isBlank()) {
            link(db, parent, taskId, false);
        } else if (historical && Set.of("AUDIO_JOB", "TRANSCODE").contains(text(row, "type"))) {
            // No scan ancestry can be inferred from a song or timestamp.
            String legacy = synthetic(db, text(row, "type"), "legacy:" + text(row, "source_id") + ":" + text(row, "type"),
                    "未记录原始批次 · " + (text(row, "type").equals("AUDIO_JOB") ? "音轨任务" : "离线转码"),
                    text(row, "source_id"), text(first(db.query("sourceLabel", args("sourceId", row.get("source_id")))), "name"), "LEGACY", number(row, "created_at"));
            link(db, legacy, taskId, true);
            aggregateOrDefer(db, legacy);
        }
    }
    String synthetic(Access db, String type, String business, String name, String sourceId, String subject, String kind, long created) {
        String taskId = id(type, business);
        if (!db.query("find", args("id", taskId)).isEmpty()) return taskId;
        long now = System.currentTimeMillis();
        var row = args("id", taskId, "type", type, "business_id", business, "parent_id", null,
                "name", limit(name + (subject.isBlank() ? "" : " · " + subject), 512), "subject_name", limit(subject, 512),
                "source_id", sourceId.isBlank() ? null : sourceId, "song_id", null,
                "status", "COMPLETED", "business_status", "COMPLETED", "stage", kind.equals("LEGACY") ? "LEGACY" : "DISCOVERING",
                "completed", null, "total", null, "progress_unit", "ITEMS", "statistics_json", "{\"statistics\":{},\"execution\":{}}",
                "error", "", "created_at", created, "updated_at", now, "started_at", null, "finished_at", null,
                "expires_at", null, "items_available", 0, "time_incomplete", kind.equals("LEGACY") ? 1 : 0, "fingerprint", taskId);
        db.write("insertProjected", row);
        db.write("initializeBatch", args("id", taskId, "kind", kind, "sealed", kind.equals("AUTOMATIC") ? 0 : 1));
        return taskId;
    }
    void link(Access db, String batchId, String taskId, boolean newOwner) {
        if (batchId.equals(taskId)) return;
        db.write("linkTask", args("batchId", batchId, "taskId", taskId, "now", System.currentTimeMillis()));
        if (newOwner) db.write("assignTask", args("batchId", batchId, "taskId", taskId));
    }
    void changed(Access db, String taskId) {
        var row = first(db.query("find", args("id", taskId)));
        if (row.isEmpty()) return;
        var owners = new LinkedHashSet<String>();
        for (var owner : db.query("relationOwners", args("id", taskId))) owners.add(text(owner, "id"));
        if (text(row, "type").equals("AUDIO_JOB") && !ACTIVE.contains(text(row, "status"))) {
            var source = first(db.query("source", args("type", "AUDIO_JOB", "businessId", row.get("business_id"))));
            if (source.isEmpty()) {
                for (var owner : db.query("scopeOwners", args("jobId", row.get("business_id")))) owners.add(text(owner, "id"));
                db.write("advanceScope", args("jobId", row.get("business_id"), "state", "INTERRUPTED", "reason", "原音轨任务已移除", "uncertain", 0, "now", System.currentTimeMillis()));
            } else {
                String state = text(row, "status");
                boolean probe = text(source, "kind").equals("PROBE");
                String next = state.equals("COMPLETED") ? probe ? "PENDING" : "DONE" : state;
                for (var owner : db.query("scopeOwners", args("jobId", row.get("business_id")))) owners.add(text(owner, "id"));
                db.write("advanceScope", args("jobId", row.get("business_id"), "state", next,
                        "reason", probe && next.equals("PENDING") ? "等待快速识别阶段判断" : text(row, "error"),
                        "uncertain", read(source.get("result_json")).path("state").asText().equals("UNKNOWN") ? 1 : 0,
                        "now", System.currentTimeMillis()));
            }
        }
        if (number(row, "is_batch") != 0) aggregateOrDefer(db, taskId);
        for (String owner : owners) aggregateOrDefer(db, owner);
    }
    void aggregate(Access db, String batchId) {
        var row = first(db.query("find", args("id", batchId)));
        if (row.isEmpty() || number(row, "is_batch") == 0) return;
        String kind = text(row, "batch_kind"), own = text(row, "status");
        boolean chain = pipeline(row), legacy = kind.equals("LEGACY");
        var counts = new TreeMap<String, Long>();
        for (String key : List.of("total", "completed", "queued", "running", "failed", "cancelled", "interrupted", "skipped", "review", "uncertain", "pendingDiscovery")) counts.put(key, 0L);
        var states = new HashMap<String, Long>();
        Long last = time(row.get("finished_at"));
        Long legacyExpiry = null;
        boolean missingFinished = false;
        if (chain || legacy) {
            for (var group : db.query("relationCounts", args("id", batchId))) {
                String state = text(group, "status"); long amount = number(group, "amount");
                states.put(state, amount); counts.merge("total", amount, Long::sum);
                counts.merge("uncertain", number(group, "uncertain"), Long::sum);
                Long at = time(group.get("last_finished")); if (at != null && (last == null || at > last)) last = at;
                Long expiry = time(group.get("last_expiry"));
                if (expiry != null && (legacyExpiry == null || expiry > legacyExpiry)) legacyExpiry = expiry;
                missingFinished |= number(group, "missing_finished") > 0;
            }
            counts.put("completed", states.getOrDefault("COMPLETED", 0L));
            counts.put("queued", states.getOrDefault("QUEUED", 0L));
            counts.put("running", states.getOrDefault("RUNNING", 0L));
            counts.put("failed", states.getOrDefault("FAILED", 0L) + states.getOrDefault("EXPIRED", 0L) + states.getOrDefault("BLOCKED", 0L));
            counts.put("cancelled", states.getOrDefault("CANCELLED", 0L));
            counts.put("interrupted", states.getOrDefault("INTERRUPTED", 0L));
            for (var scope : db.query("scopeCounts", args("id", batchId))) {
                String state = text(scope, "state"); long amount = number(scope, "amount");
                if (state.equals("PENDING")) counts.merge("pendingDiscovery", amount, Long::sum);
                if (state.equals("SKIPPED")) counts.merge("skipped", amount, Long::sum);
                if (state.equals("FAILED")) counts.put("failed", Math.max(counts.get("failed"), amount));
                if (state.equals("INTERRUPTED")) counts.put("interrupted", Math.max(counts.get("interrupted"), amount));
                if (Set.of("PROBING", "FAST").contains(state) && counts.get("running") + counts.get("queued") == 0)
                    counts.merge("pendingDiscovery", amount, Long::sum);
            }
            counts.merge("queued", counts.get("pendingDiscovery"), Long::sum);
        } else {
            var statistics = read(row.get("statistics_json")).path("statistics");
            var nativeCounts = statistics.path("counts");
            if (number(row, "items_available") != 0 && nativeCounts.isObject()) {
                counts.put("total", number(row, "total"));
                counts.put("queued", statistics.path("pending").asLong());
                counts.put("running", statistics.path("processing").asLong());
                counts.put("failed", statistics.path("failed").asLong());
                counts.put("review", statistics.path("review").asLong());
                counts.put("skipped", statistics.path("skipped").asLong());
                counts.put("cancelled", nativeCounts.path("CANCELLED").asLong());
                counts.put("interrupted",nativeCounts.path("INTERRUPTED").asLong());
                counts.put("completed", Math.max(0, number(row, "completed") - counts.get("failed") - counts.get("cancelled") - counts.get("interrupted")));
            } else {
                counts.put("total", 1L);
                String key = switch (own) { case "RUNNING" -> "running"; case "QUEUED" -> "queued";
                    case "COMPLETED" -> "completed"; case "FAILED", "BLOCKED", "EXPIRED" -> "failed";
                    case "CANCELLED" -> "cancelled"; case "INTERRUPTED" -> "interrupted"; default -> ""; };
                if (!key.isBlank()) counts.put(key, 1L);
                if (text(row, "type").equals("CACHE_CLEANUP")) {
                    long removed = statistics.path("removed").asLong(), skipped = statistics.path("skipped").asLong(), failed = statistics.path("failed").asLong();
                    counts.put("total", removed + skipped + failed); counts.put("completed", removed + skipped);
                    counts.put("skipped", skipped); counts.put("failed", failed);
                }
                counts.put("uncertain", read(row.get("statistics_json")).path("execution").path("resultState").asText().equals("UNKNOWN") ? 1L : 0L);
            }
        }
        String status = own;
        if (!ACTIVE.contains(own) && (own.equals("COMPLETED") && counts.get("failed") > 0
                || Set.of("FAILED", "CANCELLED", "INTERRUPTED").contains(own) && counts.get("completed") > 0)) status = "PARTIAL";
        boolean sealed = number(row, "scope_sealed") != 0;
        if (chain || legacy) {
            if (counts.get("running") > 0 || own.equals("RUNNING")) status = "RUNNING";
            else if (states.containsKey("CANCELLING") || own.equals("CANCELLING")) status = "CANCELLING";
            else if (counts.get("queued") > 0 || !sealed || own.equals("QUEUED")) status = "QUEUED";
            else if (states.containsKey("PAUSED") || own.equals("PAUSED")) status = "PAUSED";
            else if (states.containsKey("AWAITING_CONFIRMATION") || own.equals("AWAITING_CONFIRMATION")) status = "AWAITING_CONFIRMATION";
            else {
                var outcomes = new LinkedHashSet<String>();
                if (!own.equals("COMPLETED")) outcomes.add(own);
                if (counts.get("failed") > 0) outcomes.add("FAILED");
                if (counts.get("cancelled") > 0) outcomes.add("CANCELLED");
                if (counts.get("interrupted") > 0) outcomes.add("INTERRUPTED");
                if (outcomes.isEmpty()) status = "COMPLETED";
                else if (outcomes.size() == 1 && counts.get("completed") == 0 && counts.get("skipped") == 0) status = outcomes.iterator().next();
                else status = "PARTIAL";
            }
        }
        String stage = text(row, "stage");
        if (chain && !ACTIVE.contains(own) && ACTIVE.contains(status)) stage = counts.get("running") > 0 ? "RECOGNIZING" : "WAITING_RECOGNITION";
        if (legacy) stage = "LEGACY";
        long now = System.currentTimeMillis();
        Long finished = ACTIVE.contains(status) ? null : legacy ? missingFinished ? null : last : time(row.get("batch_finished_at"));
        if (finished == null && !ACTIVE.contains(status)) {
            if (!legacy) {
                finished = last;
                if (ACTIVE.contains(text(row, "batch_status"))) finished = last == null ? now : Math.max(last, now);
            }
        }
        Long expiry = ACTIVE.contains(status) ? null : finished != null ? finished + RETENTION
                : legacy && legacyExpiry != null ? legacyExpiry
                : row.get("expires_at") instanceof Number n ? n.longValue() : number(row, "updated_at") + RETENTION;
        String encoded = encode(counts);
        boolean change = !status.equals(text(row, "batch_status")) || !stage.equals(text(row, "batch_stage"))
                || !encoded.equals(text(row, "batch_counts_json")) || !Objects.equals(finished, time(row.get("batch_finished_at")))
                || !Objects.equals(expiry, time(row.get("batch_expires_at")));
        if (change) db.write("updateBatch", args("id", batchId, "batch_status", status, "batch_stage", stage,
                "batch_counts_json", encoded, "batch_finished_at", finished, "batch_expires_at", expiry, "batch_updated_at", now));
    }
}
