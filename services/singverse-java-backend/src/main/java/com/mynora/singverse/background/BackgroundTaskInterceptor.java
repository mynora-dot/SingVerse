package com.mynora.singverse.background;

import java.util.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

/**
 * Observes the existing persistence boundaries, including recovery/settings/cleanup paths.
 * Uses the current executor, never opens another connection (SQLite has a one-connection pool).
 * All projection queries and writes remain explicit statements in mappers/background/.
 */
@Component
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
public class BackgroundTaskInterceptor implements Interceptor {
    private final BackgroundTaskProjector projector;
    public BackgroundTaskInterceptor(BackgroundTaskProjector projector) { this.projector = projector; }

    public Object intercept(Invocation invocation) throws Throwable {
        var statement = (MappedStatement) invocation.getArgs()[0];
        String name = statement.getId();
        String type = type(name);
        if (type == null) return invocation.proceed();
        String operation = name.substring(name.lastIndexOf('.') + 1);
        if (!observed(type, operation)) return invocation.proceed();
        var executor = (Executor) invocation.getTarget();
        BackgroundTaskProjector.Access db = new BackgroundTaskProjector.Access() {
            public List<Map<String, Object>> query(String id, Map<String, Object> args) {
                try {
                    return executor.query(statement.getConfiguration().getMappedStatement(BackgroundTaskProjector.NS + id),
                            args, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
                } catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
            }
            public int write(String id, Map<String, Object> args) {
                try { return executor.update(statement.getConfiguration().getMappedStatement(BackgroundTaskProjector.NS + id), args); }
                catch (java.sql.SQLException error) { throw new IllegalStateException(error); }
            }
        };
        Object parameter = invocation.getArgs()[1];
        Map<?, ?> values = parameter instanceof Map<?, ?> map ? map : Map.of();
        boolean bulk = Set.of("recover", "recoverItems", "pauseActive", "recoverTasks", "pauseAll",
                "recoverPreparing", "collectPreviewItems", "collectPreviews", "prune").contains(operation);
        var ids = new LinkedHashSet<String>();
        if (bulk) {
            String cursor = "";
            while (true) {
                var rows = db.query("sourceKeys", BackgroundTaskProjector.args("type", type, "cursor", cursor));
                if (rows.isEmpty()) break;
                for (var row : rows) {
                    String raw = BackgroundTaskProjector.text(row, "raw_status");
                    if (operation.startsWith("collect") || operation.equals("prune")
                            || BackgroundTaskProjector.SOURCE_ACTIVE.contains(raw))
                        ids.add(BackgroundTaskProjector.text(row, "id"));
                }
                cursor = BackgroundTaskProjector.text(rows.getLast(), "id");
            }
        } else if (type.equals("AUDIO_BATCH") && operation.equals("insertItems") && values.get("rows") instanceof List<?> rows) {
            for (var row : rows) if (row instanceof Map<?, ?> map) add(ids, map.get("batch"));
        } else if (type.equals("METADATA") && operation.equals("insertItem")) {
            add(ids, values.get("batchId"));
        } else if (type.equals("CLASSIFICATION") && operation.equals("insertItem")) {
            add(ids, values.get("task"));
        } else if (type.equals("AUDIO_BATCH") && operation.equals("updateItem")) {
            add(ids, values.get("batch"));
        } else if ((type.equals("AUDIO_JOB") && operation.equals("insertJob") || type.equals("TRANSCODE") && operation.equals("insert"))
                && values.get("row") instanceof Map<?, ?> row) {
            add(ids, row.get("id"));
        } else if (type.equals("CACHE_CLEANUP") && operation.equals("insertJob") && values.get("v") instanceof Map<?, ?> row) {
            add(ids, row.get("id"));
        } else if (type.equals("LX_DOWNLOAD") && Set.of("progress","updateItem").contains(operation)
                || (type.equals("METADATA") && operation.equals("itemStatus"))
                || type.equals("CLASSIFICATION") && Set.of("itemState", "retry").contains(operation)) {
            // The item still exists before and after its mutation; locate its parent without copying input/configuration.
            for (var row : db.query("owner", BackgroundTaskProjector.args("type", type, "itemId", values.get("id"))))
                add(ids, row.get("id"));
        } else {
            add(ids, values.get("id"));
        }
        boolean deleting = operation.equals("delete") || operation.equals("deleteJob") || operation.startsWith("collect") || operation.equals("prune");
        if (deleting) for (String id : ids) projector.refresh(db, type, id, true, true);
        Object result = invocation.proceed();
        if (result instanceof Number changed && changed.intValue() > 0) {
            for (String id : ids) {
                boolean force = deleting || !Set.of("progress", "prepared", "insertItem", "insertItems", "itemStatus",
                        "itemState", "updateItem", "updateJob").contains(operation);
                boolean historical = operation.startsWith("recover") || operation.equals("pauseActive") || operation.equals("pauseAll")
                        || Set.of("AUDIO_JOB", "TRANSCODE").contains(type) && !Set.of("insertJob", "insert").contains(operation)
                        && db.query("find", BackgroundTaskProjector.args("id", BackgroundTaskProjector.id(type, id))).isEmpty();
                projector.refresh(db, type, id, historical, force);
                if (type.equals("AUDIO_JOB"))
                    for (var parent : db.query("parents", BackgroundTaskProjector.args("businessId", id)))
                        projector.refresh(db, "AUDIO_BATCH", BackgroundTaskProjector.text(parent, "id"), false, false);
            }
        }
        return result;
    }

    private static void add(Set<String> ids, Object value) {
        if (value != null && !value.toString().isBlank()) ids.add(value.toString());
    }
    private static String type(String name) {
        if (name.contains(".download.LxMapper.")) return "LX_DOWNLOAD";
        if (name.contains(".webdav.ScanTaskMapper.")) return "WEBDAV_SCAN";
        if (name.contains(".metadata.MetadataMapper.")) return "METADATA";
        if (name.contains(".classification.ClassificationMapper.")) return "CLASSIFICATION";
        if (name.contains(".audio.AudioProcessingMapper.")) return "AUDIO_BATCH";
        if (name.contains(".audio.AudioRoleMapper.")) return "AUDIO_JOB";
        if (name.contains(".transcoding.TranscodeMapper.")) return "TRANSCODE";
        if (name.contains(".transcoding.SongCacheMapper.")) return "CACHE_CLEANUP";
        if (name.contains(".webdav.metadata.WebDavShareJobMapper.")) return "SHARE_EXPORT";
        return null;
    }
    private static boolean observed(String type, String operation) {
        return switch (type) {
            case "LX_DOWNLOAD" -> Set.of("updateBatch","progress","updateItem").contains(operation);
            case "WEBDAV_SCAN" -> Set.of("insert", "start", "progress", "cancel", "committing", "finish", "recover", "prune").contains(operation);
            case "METADATA" -> Set.of("insertBatch", "insertItem", "itemStatus", "batchStatus", "recoverItems", "pauseActive").contains(operation);
            case "CLASSIFICATION" -> Set.of("insertTask", "insertItem", "itemState", "retry", "taskState", "cancelItems",
                    "recoverItems", "recoverTasks", "pauseAll").contains(operation);
            case "AUDIO_BATCH" -> Set.of("insertBatch", "insertItems", "prepared", "ready", "preparationFailed", "recoverPreparing",
                    "start", "cancel", "finish", "updateItem", "skipCancelled", "collectPreviewItems", "collectPreviews").contains(operation);
            case "AUDIO_JOB" -> Set.of("insertJob", "updateJob").contains(operation);
            case "TRANSCODE" -> Set.of("insert", "update", "requeue", "delete").contains(operation);
            case "CACHE_CLEANUP" -> Set.of("insertJob", "updateJob").contains(operation);
            case "SHARE_EXPORT" -> Set.of("insert", "progress", "count", "sourceCount", "deleteJob").contains(operation);
            default -> false;
        };
    }
}
