package com.mynora.singverse.background;

import static com.mynora.singverse.background.BackgroundTaskProjector.*;

import com.mynora.singverse.infrastructure.database.DatabaseInitializer;
import com.mynora.singverse.media.dto.MediaSongDtos;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BackgroundTaskService {
    private final BackgroundTaskMapper mapper;
    private final BackgroundTaskProjector projector;
    private final BackgroundTaskBatches batches;
    private final Access db;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;
    private final AtomicBoolean maintaining = new AtomicBoolean();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "background-task-projection"); thread.setDaemon(true); return thread;
    });
    private volatile boolean bootstrapped;
    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "PAUSED", "CANCELLING",
            "AWAITING_CONFIRMATION", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED", "BLOCKED", "EXPIRED");

    public BackgroundTaskService(BackgroundTaskMapper mapper, BackgroundTaskProjector projector,
            SqlSessionTemplate session, PlatformTransactionManager manager, DatabaseInitializer database) {
        this.mapper = mapper; this.projector = projector; this.batches = projector.batches;
        tx = new TransactionTemplate(manager); read = new TransactionTemplate(manager); read.setReadOnly(true);
        db = new Access() {
            public List<Map<String, Object>> query(String statement, Map<String, Object> args) { return session.selectList(NS + statement, args); }
            public int write(String statement, Map<String, Object> args) { return session.update(NS + statement, args); }
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        long now = System.currentTimeMillis();
        mapper.interruptLocal(args("now", now, "expires", now + RETENTION));
        String cursor = "";
        while (true) {
            var rows = db.query("localRecoveries", args("cursor", cursor));
            if (rows.isEmpty()) break;
            for (var row : rows) tx.executeWithoutResult(ignored -> batches.changed(db, text(row, "id")));
            cursor = text(rows.getLast(), "id");
        }
        db.write("sealAutomatic", args("round", null));
        queueMaintenance();
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void queueMaintenance() {
        if (!maintaining.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { maintain(); }
                catch (RuntimeException error) {
                    LoggerFactory.getLogger(getClass()).warn("Task display reconciliation deferred ({})", error.getClass().getSimpleName());
                } finally { maintaining.set(false); }
            });
        } catch (RejectedExecutionException stopped) { maintaining.set(false); }
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void queueProgress() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    for (var pending : projector.drain())
                        tx.executeWithoutResult(status -> projector.refresh(db, pending.type(), pending.businessId(), false, true));
                } catch (RuntimeException error) {
                    LoggerFactory.getLogger(getClass()).warn("Task display progress deferred ({})", error.getClass().getSimpleName());
                } finally { flushing.set(false); }
            });
        } catch (RejectedExecutionException stopped) { flushing.set(false); }
    }
    void maintain() {
        if (!bootstrapped) {
            for (String type : TYPES) {
                String cursor = "";
                while (!Thread.currentThread().isInterrupted()) {
                    var keys = mapper.sourceKeys(args("type", type, "cursor", cursor));
                    if (keys.isEmpty()) break;
                    importPage(() -> { for (var key : keys) {
                        Long at = time(key.get("updated_at"));
                        if (at == null) at = time(key.get("created_at"));
                        if (SOURCE_ACTIVE.contains(text(key, "raw_status")) || at != null && at >= System.currentTimeMillis() - RETENTION) {
                            String businessId = text(key, "id");
                            tx.executeWithoutResult(status -> projector.refresh(db, type, businessId, true, true));
                        }
                    } });
                    cursor = text(keys.getLast(), "id");
                }
            }
            while (true) {
                var rows = db.query("unclassified", Map.of());
                if (rows.isEmpty()) break;
                importPage(() -> rows.forEach(row -> tx.executeWithoutResult(ignored -> batches.classify(db, text(row, "id"), true))));
            }
            bootstrapped = true;
        }
        String cursor = "";
        while (!Thread.currentThread().isInterrupted()) {
            var keys = mapper.active(args("cursor", cursor));
            if (keys.isEmpty()) break;
            for (var key : keys)
                if (!text(key, "type").equals("LOCAL_SCAN"))
                    tx.executeWithoutResult(status -> projector.refresh(db, text(key, "type"), text(key, "business_id"), true, true));
            cursor = text(keys.getLast(), "id");
        }
        String batchCursor = "";
        while (!Thread.currentThread().isInterrupted()) {
            var rows = db.query("batchKeys", args("cursor", batchCursor));
            if (rows.isEmpty()) break;
            for (var row : rows) tx.executeWithoutResult(ignored -> batches.aggregate(db, text(row, "id")));
            batchCursor = text(rows.getLast(), "id");
        }
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            var expired = mapper.expired(args("now", now));
            if (expired.isEmpty()) break;
            for (var key : expired) tx.executeWithoutResult(status -> {
                var parameters = args("id", key.get("id"), "now", now);
                if (mapper.lockExpired(parameters) == 0) return;
                var row = mapper.find(parameters);
                Object expiry = row.get(number(row, "is_batch") != 0 ? "batch_expires_at" : "expires_at");
                if (!(expiry instanceof Number n) || n.longValue() > now || !mapper.memberships(parameters).isEmpty()) return;
                db.write("deleteRelations", parameters); db.write("deleteScopes", parameters);
                mapper.deleteItems(parameters); mapper.deleteTask(parameters);
            });
        }
    }

    private void importPage(Runnable work) {
        // A large legacy batch is counted once per imported page, rather than once per child.
        var changed = batches.beginImport();
        try { work.run(); }
        finally {
            batches.endImport();
            for (String id : changed) tx.executeWithoutResult(ignored -> batches.aggregate(db, id));
        }
    }

    public Map<String, Object> list(String view, String type, String status, String q, int page, int size) {
        page(page, size);
        if (!Set.of("ACTIVE", "HISTORY", "ALL").contains(view)) throw bad("任务视图无效");
        if (type != null && !type.isBlank() && !TYPES.contains(type) && !Set.of("LOCAL_SCAN", "AUDIO_DISCOVERY").contains(type)) throw bad("任务类型无效");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("任务状态无效");
        if (q.length() > 200 || q.chars().anyMatch(Character::isISOControl)) throw bad("搜索文本无效");
        String pattern = q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var filter = args("view", view, "type", blank(type), "status", blank(status), "pattern", pattern,
                "offset", (long) page * size, "size", size, "now", System.currentTimeMillis());
        return read.execute(ignored -> args("items", mapper.list(filter).stream().map(this::view).toList(),
                "page", page, "size", size, "totalElements", mapper.count(filter), "observedAt", Instant.now().toString()));
    }
    public Map<String, Object> detail(String id) {
        return read.execute(ignored -> view(require(id)));
    }
    public Map<String, Object> items(String id, String status, int page, int size) {
        page(page, size);
        if (status != null && (status.length() > 30 || !status.matches("[A-Z_]*"))) throw bad("明细状态无效");
        return read.execute(ignored -> {
            require(id);
            var parameters = args("taskId", id, "status", blank(status), "offset", (long) page * size, "size", size);
            var states = new TreeMap<String, Long>();
            mapper.itemStates(parameters).forEach(row -> states.put(text(row, "status"), number(row, "amount")));
            return args("items", mapper.items(parameters).stream().map(row -> args("id", row.get("id"), "songId", row.get("song_id"),
                            "title", row.get("title"), "status", row.get("status"), "message", row.get("message"),
                            "execution", projector.read(row.get("execution_json")))).toList(),
                    "page", page, "size", size, "totalElements", mapper.itemCount(parameters), "counts", states);
        });
    }
    public Map<String, Object> children(String id, String type, String status, int page, int size) {
        page(page, size);
        if (type != null && !type.isBlank() && !TYPES.contains(type) && !Set.of("LOCAL_SCAN", "AUDIO_DISCOVERY").contains(type)) throw bad("任务类型无效");
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("任务状态无效");
        return read.execute(ignored -> {
            var root = require(id);
            var parameters = args("taskId", id, "type", blank(type), "status", blank(status), "offset", (long) page * size, "size", size);
            var states = new TreeMap<String, Long>();
            mapper.childStates(parameters).forEach(row -> states.put(text(row, "status"), number(row, "amount")));
            var rows = mapper.children(parameters);
            long count = mapper.childCount(parameters);
            // A standalone operation is a real batch of one. Native item snapshots use /items instead.
            boolean self = states.isEmpty() && number(root, "items_available") == 0 && !BackgroundTaskBatches.pipeline(root)
                    && !text(root, "batch_kind").equals("LEGACY");
            if (self) {
                states.put(text(root, "status"), 1L);
                boolean matches = (blank(type) == null || type.equals(text(root, "type"))) && (blank(status) == null || status.equals(text(root, "status")));
                count = matches ? 1 : 0;
                rows = matches && page == 0 ? List.of(root) : List.of();
            }
            return args("items", rows.stream().map(row -> view(row, true)).toList(), "page", page, "size", size,
                    "totalElements", count, "counts", states, "observedAt", Instant.now().toString());
        });
    }
    private Map<String, Object> require(String id) {
        long now = System.currentTimeMillis();
        var row = mapper.find(args("id", id));
        if (row == null || row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务记录不存在或已超过保留期限");
        Object expiry = row.get(number(row, "is_batch") != 0 ? "batch_expires_at" : "expires_at");
        if (expiry instanceof Number n && n.longValue() <= now && mapper.memberships(args("id", id, "now", now)).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务记录不存在或已超过保留期限");
        return row;
    }
    private Map<String, Object> view(Map<String, Object> row) { return view(row, false); }
    private Map<String, Object> view(Map<String, Object> row, boolean operationOnly) {
        var detail = projector.read(row.get("statistics_json"));
        boolean batch = !operationOnly && number(row, "is_batch") != 0;
        boolean chain = BackgroundTaskBatches.pipeline(row) || text(row, "batch_kind").equals("LEGACY");
        Long completed = row.get("completed") instanceof Number n ? n.longValue() : null;
        Long total = row.get("total") instanceof Number n ? n.longValue() : null;
        Double percent = completed != null && total != null && total > 0 ? Math.min(100d, Math.max(0d, completed * 100d / total)) : null;
        var progress = args("completed", completed, "total", total, "percent", percent, "unit", row.get("progress_unit"));
        var original = args("status", row.get("status"), "businessStatus", row.get("business_status"), "stage", row.get("stage"),
                "progress", progress, "finishedAt", iso(row.get("finished_at")));
        String type = text(row, "type"), businessId = text(row, "business_id");
        String status = batch && row.get("batch_status") != null ? text(row, "batch_status") : text(row, "status");
        String stage = batch && row.get("batch_stage") != null ? text(row, "batch_stage") : text(row, "stage");
        if (batch && chain) progress = args("completed", null, "total", null, "percent", null, "unit", "ITEMS");
        var parents = mapper.memberships(args("id", row.get("id"), "now", System.currentTimeMillis()));
        return args("id", row.get("id"), "type", type, "businessId", businessId, "name", row.get("name"),
                "isBatch", batch, "batchKind", row.get("batch_kind"), "scopeSealed", number(row, "scope_sealed") != 0,
                "parentId", row.get("parent_id"), "batches", parents.stream().map(parent -> args("id", parent.get("id"), "name", parent.get("name"))).toList(),
                "childrenCounts", projector.read(row.get("batch_counts_json")), "detailMode", number(row, "items_available") != 0 ? "ITEMS" : "CHILDREN",
                "operation", original, "subjectName", row.get("subject_name"), "sourceId", row.get("source_id"), "songId", row.get("song_id"),
                "status", status, "businessStatus", row.get("business_status"), "stage", stage,
                "progress", progress, "statistics", detail.path("statistics"), "execution", detail.path("execution"), "error", batch && chain && text(row, "error").isBlank()
                        ? text(first(db.query("scopeFailure", args("id", row.get("id")))), "reason") : row.get("error"),
                "waitingReason", batch && chain ? text(first(db.query("scopeReason", args("id", row.get("id")))), "reason") : "",
                "createdAt", iso(row.get("created_at")), "updatedAt", iso(Math.max(number(row, "updated_at"), number(row, "batch_updated_at"))),
                "startedAt", iso(row.get("started_at")), "finishedAt", iso(batch ? row.get("batch_finished_at") : row.get("finished_at")),
                "itemsAvailable", number(row, "items_available") != 0, "timeIncomplete", number(row, "time_incomplete") != 0,
                "relatedAvailable", number(row, "subject_available") != 0, "relatedPath", related(row), "observedAt", Instant.now().toString());
    }
    private static String related(Map<String, Object> row) {
        if (text(row, "batch_kind").equals("LEGACY")) return text(row, "type").equals("TRANSCODE") ? "/library/songs" : "/library/audio-processing";
        if (text(row, "batch_kind").equals("AUTOMATIC")) return "/library/audio-processing";
        String business = text(row, "business_id"), source = text(row, "source_id"), song = text(row, "song_id");
        return switch (text(row, "type")) {
            case "METADATA" -> "/library/songs/metadata?batch=" + encodeId(business);
            case "LX_DOWNLOAD" -> "/library/downloads?tab=records&batch=" + encodeId(business);
            case "CLASSIFICATION" -> "/library/classification?task=" + encodeId(business);
            case "AUDIO_BATCH" -> "/library/audio-processing?tab=tasks&batch=" + encodeId(business);
            case "AUDIO_JOB" -> "/library/audio-processing?song=" + encodeId(song);
            case "TRANSCODE" -> "/library/songs?detailSongId=" + encodeId(song);
            case "CACHE_CLEANUP" -> "/library/cache?job=" + encodeId(business);
            default -> "/library/media?source=" + encodeId(source);
        };
    }
    private static String encodeId(String value) { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Object iso(Object value) { Long timestamp = time(value); return timestamp == null ? null : Instant.ofEpochMilli(timestamp).toString(); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static void page(int page, int size) { if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数无效"); }
    private static ResponseStatusException bad(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }

    /** Local scans retain their synchronous API; this records their actual server-side lifetime. */
    public String startLocal(String sourceId) {
        var source = mapper.localSource(args("sourceId", sourceId));
        if (source == null || source.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String business = UUID.randomUUID().toString(), taskId = id("LOCAL_SCAN", business);
        var row = args("id", taskId, "type", "LOCAL_SCAN", "business_id", business, "parent_id", null,
                "name", limit("本地扫描 · " + text(source, "name"), 512), "subject_name", limit(text(source, "name"), 512),
                "source_id", sourceId, "song_id", null, "status", "RUNNING", "business_status", "RUNNING", "stage", "SCANNING",
                "completed", null, "total", null, "progress_unit", "SONGS", "statistics_json", "{\"statistics\":{},\"execution\":{}}",
                "error", "", "created_at", now, "updated_at", now, "started_at", now, "finished_at", null,
                "expires_at", null, "items_available", 0, "time_incomplete", 0, "fingerprint", UUID.randomUUID().toString());
        return tx.execute(ignored -> {
            mapper.insert(row); batches.classify(db, taskId, false); batches.changed(db, taskId); return taskId;
        });
    }
    public void localProgress(String taskId, String stage, long entries, long discovered, Long completed, Long total) {
        if (taskId == null) return;
        tx.executeWithoutResult(ignored -> {
            var row = mapper.find(args("id", taskId));
            if (row == null || !text(row, "status").equals("RUNNING")) return;
            row.put("stage", stage); row.put("updated_at", System.currentTimeMillis());
            row.put("completed", completed); row.put("total", total);
            row.put("statistics_json", projector.encode(args("statistics", args("entries", entries, "total_songs", discovered), "execution", Map.of())));
            row.put("fingerprint", UUID.randomUUID().toString()); mapper.update(row); batches.changed(db, taskId);
        });
    }
    public void finishLocal(String taskId, MediaSongDtos.Scan result, String error) {
        if (taskId == null) return;
        tx.executeWithoutResult(ignored -> {
            var row = mapper.find(args("id", taskId)); if (row == null) return;
            long now = System.currentTimeMillis();
            row.put("status", result == null ? "FAILED" : "COMPLETED"); row.put("business_status", result == null ? "FAILED" : "SUCCEEDED");
            row.put("stage", result == null ? "FAILED" : "COMPLETED"); row.put("error", message(Objects.toString(error, "")));
            if (result != null) {
                row.put("completed", (long) result.totalSongs()); row.put("total", (long) result.totalSongs());
                row.put("statistics_json", projector.encode(args("statistics", args("total_songs", result.totalSongs(),
                        "matched_songs", result.matchedSongs(), "review_songs", result.needsReviewSongs(), "skipped_files", result.skippedFiles()), "execution", Map.of())));
            }
            row.put("updated_at", now); row.put("finished_at", now); row.put("expires_at", now + RETENTION);
            row.put("fingerprint", UUID.randomUUID().toString()); mapper.update(row);
            db.write("sealScope", args("id", taskId)); batches.changed(db, taskId);
        });
    }
    /** Called in the index publication transaction, before discovery can observe the songs. */
    public void registerScanScope(String batchId, String sourceId) {
        if (batchId == null) return;
        tx.executeWithoutResult(ignored -> {
            var root = first(db.query("find", args("id", batchId)));
            String scanBusiness = text(root, "type").equals("WEBDAV_SCAN") ? text(root, "business_id") : null;
            String cursor = "";
            while (true) {
                var rows = db.query("scopeSongs", args("sourceId", sourceId, "cursor", cursor, "scanBusiness", scanBusiness));
                if (rows.isEmpty()) break;
                var admitted = new ArrayList<Map<String, Object>>();
                for (var row : rows) {
                    Long size = row.get("file_size") instanceof Number n ? n.longValue() : null;
                    String modified = (String) row.get("modified_at");
                    String fp = com.mynora.singverse.media.repository.MediaSongRepository.fingerprint(text(row, "id"), size, modified);
                    admitted.add(args("batchId", batchId, "songId", row.get("id"), "fingerprint", fp, "now", System.currentTimeMillis()));
                }
                db.write("admitScopes", args("rows", admitted));
                cursor = text(rows.getLast(), "id");
            }
            db.write("sealScope", args("id", batchId)); batches.aggregate(db, batchId);
        });
    }
    public static String scanBatchId(String businessId) { return id("WEBDAV_SCAN", businessId); }
    public static String audioBatchId(String businessId) { return id("AUDIO_BATCH", businessId); }
    public <T> T audioTransaction(java.util.function.Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    public List<String> audioOrigins(String songId, String fingerprint) {
        return db.query("origins", args("songId", songId, "fingerprint", fingerprint)).stream().map(row -> text(row, "batch_id")).toList();
    }
    public List<Map<String, Object>> pendingScopes(String batchCursor, String songCursor) {
        return db.query("pendingScopes", args("batchCursor", batchCursor, "songCursor", songCursor));
    }
    public List<String> automaticOrigin(String round, String songId, String fingerprint) {
        return tx.execute(ignored -> {
            var existing = audioOrigins(songId, fingerprint);
            if (!existing.isEmpty()) return existing;
            var song = first(db.query("scopeSong", args("songId", songId)));
            if (song.isEmpty()) return List.of();
            String batchId = batches.synthetic(db, "AUDIO_DISCOVERY", round + ":" + text(song, "source_id"),
                    "自动音轨识别", text(song, "source_id"), text(song, "source_name"), "AUTOMATIC", System.currentTimeMillis());
            db.write("admitScope", args("batchId", batchId, "songId", songId, "fingerprint", fingerprint, "now", System.currentTimeMillis()));
            batches.aggregate(db, batchId); return List.of(batchId);
        });
    }
    /** The caller also creates/reuses the business job in this transaction. */
    public void audioSubmitted(String jobId, String kind, String songId, String fingerprint, List<String> origins, boolean created) {
        if (origins.isEmpty()) return;
        String taskId = id("AUDIO_JOB", jobId);
        projector.refresh(db, "AUDIO_JOB", jobId, false, true);
        var task = first(db.query("find", args("id", taskId)));
        if (task.isEmpty()) return; // Locally observed media tags are not independent compute tasks.
        db.write("lockExpired", args("id", taskId));
        String state = text(task, "status");
        String scopeState = ACTIVE.contains(state) ? kind.equals("PROBE") ? "PROBING" : "FAST"
                : state.equals("COMPLETED") ? kind.equals("PROBE") ? "PENDING" : "DONE" : state;
        for (String origin : new LinkedHashSet<>(origins)) {
            batches.link(db, origin, taskId, created);
            db.write("resolveScope", args("batchId", origin, "songId", songId, "fingerprint", fingerprint,
                    "state", scopeState, "jobId", ACTIVE.contains(state) ? jobId : null, "reason", text(task, "error"),
                    "uncertain", projector.read(task.get("statistics_json")).path("execution").path("resultState").asText().equals("UNKNOWN") ? 1 : 0,
                    "now", System.currentTimeMillis()));
            batches.aggregate(db, origin);
        }
    }
    public void resolveAudioScopes(String songId, String fingerprint, String state, String reason) {
        tx.executeWithoutResult(ignored -> {
            for (String origin : audioOrigins(songId, fingerprint)) {
                db.write("resolveScope", args("batchId", origin, "songId", songId, "fingerprint", fingerprint,
                        "state", state, "jobId", null, "reason", message(reason), "uncertain", 0, "now", System.currentTimeMillis()));
                batches.aggregate(db, origin);
            }
        });
    }
    public void skipPendingAudio(String reason) {
        db.write("sealAutomatic", args("round", null));
        String cursor = "";
        while (true) {
            var rows = db.query("pendingScopeBatches", args("cursor", cursor));
            if (rows.isEmpty()) break;
            for (var row : rows) tx.executeWithoutResult(ignored -> {
                String batchId = text(row, "batch_id");
                db.write("skipBatchScopes", args("batchId", batchId, "reason", message(reason), "now", System.currentTimeMillis()));
                batches.aggregate(db, batchId);
            });
            cursor = text(rows.getLast(), "batch_id");
        }
    }
    public void waitingForAudioNode() { db.write("waitingScopes", args("reason", "等待计算节点恢复后继续发现", "now", System.currentTimeMillis())); }
    public void finishDiscoveryRound(String round) {
        tx.executeWithoutResult(ignored -> db.write("sealAutomatic", args("round", round + ":%")));
        queueMaintenance();
    }

    @jakarta.annotation.PreDestroy public void close() { worker.shutdownNow(); }
}
