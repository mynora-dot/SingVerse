package com.mynora.singverse.media.webdav;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.metadata.MetadataMapper;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.FilenameParserConfigService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebDavScanService {
    private record Context(Source source, FilenameParserConfigService.ProfileSnapshot profile, String encrypted) {}
    private final com.mynora.singverse.media.webdav.metadata.WebDavMetadataService embedded;
    private final com.mynora.singverse.media.webdav.metadata.WebDavAccess access;
    private final ScanTaskMapper tasks;
    private final MediaSourceRepository sources;
    private final MediaSourceMapper sourceMapper;
    private final MediaSongMapper songMapper;
    private final MediaSongRepository songs;
    private final MetadataMapper metadata;
    private final FilenameParserConfigService profiles;
    private final AuthCrypto crypto;
    private final WebDavClient client;
    private final TransactionTemplate transaction;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "webdav-scan"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webdav-scan-cleanup"); t.setDaemon(true); return t;
    });
    private final ConcurrentMap<String, Control> controls = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private static final class Control {
        volatile boolean cancelled;
        volatile Thread thread;
        Runnable job;
        private AutoCloseable stream;
        synchronized void track(AutoCloseable value) {
            stream = value;
            if (cancelled) closeStream();
        }
        private void closeStream() { if (stream != null) try { stream.close(); } catch (Exception ignored) { } }
        synchronized void cancel() { cancelled = true; closeStream(); if (thread != null) thread.interrupt(); }
        void check(long deadline) {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (System.nanoTime() > deadline) throw WebDavClient.failure("WebDAV 扫描超过两小时，请拆分来源后重试");
        }
    }
    public WebDavScanService(ScanTaskMapper tasks, MediaSourceRepository sources, MediaSourceMapper sourceMapper,
            MediaSongMapper songMapper, MediaSongRepository songs, MetadataMapper metadata,
            FilenameParserConfigService profiles, AuthCrypto crypto, WebDavClient client, PlatformTransactionManager manager,
            com.mynora.singverse.media.webdav.metadata.WebDavMetadataService embedded,
            com.mynora.singverse.media.webdav.metadata.WebDavAccess access) {
        this.embedded=embedded; this.access=access; this.tasks = tasks; this.sources = sources; this.sourceMapper = sourceMapper; this.songMapper = songMapper;
        this.songs = songs; this.metadata = metadata; this.profiles = profiles; this.crypto = crypto; this.client = client;
        transaction = new TransactionTemplate(manager);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        transaction.executeWithoutResult(s -> {
            tasks.recover(Instant.now().toString()); tasks.clearAbandonedSongs(); tasks.clearAbandonedDirectories();
        });
        maintenance.scheduleWithFixedDelay(() -> {
            try { prune(); } catch (RuntimeException ignored) { /* Retry next hour, never log credentials or response bodies. */ }
        }, 0, 1, TimeUnit.HOURS);
    }
    private void prune() {
        transaction.executeWithoutResult(s -> {
            tasks.prune(Instant.now().minus(Duration.ofDays(7)).toString());
            tasks.clearAbandonedSongs(); tasks.clearAbandonedDirectories();
        });
    }
    public synchronized ScanTask create(String sourceId) {
        if (stopping) throw conflict("服务正在停止，请稍后重新扫描");
        String id = UUID.randomUUID().toString();
        Context context = transaction.execute(s -> {
            profiles.lockBindings();
            Source source = sources.get(sourceId);
            if (!"WEBDAV".equals(source.type())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本地目录请使用原扫描入口");
            if (!tasks.activeSource(sourceId).isEmpty()) throw conflict("该媒体来源正在扫描，请稍后重试");
            if (tasks.activeCount() >= 34) throw conflict("后台扫描队列已满，请稍后重试");
            embedded.prepare(source);
            Context snapshot = new Context(source, profiles.savedProfile(source.parserProfileId()),
                    (String) sources.find(sourceId).get("password_encrypted"));
            tasks.insert(id, sourceId, Instant.now().toString());
            return snapshot;
        });
        Control control = new Control(); controls.put(id, control);
        control.job = () -> run(id, Objects.requireNonNull(context), control);
        try { executor.execute(control.job); }
        catch (RejectedExecutionException e) {
            controls.remove(id); finish(id, "FAILED", "后台扫描队列已满，请重新扫描");
            throw conflict("后台扫描队列已满，请稍后重试");
        }
        return get(sourceId, id);
    }
    public ScanTask latest(String sourceId) {
        sources.get(sourceId);
        var rows = tasks.latest(sourceId); return rows.isEmpty() ? null : task(rows.getFirst());
    }
    public ScanTask get(String sourceId, String id) {
        sources.get(sourceId);
        var rows = tasks.find(id);
        if (rows.isEmpty() || !sourceId.equals(rows.getFirst().get("source_id")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "扫描任务不存在");
        return task(rows.getFirst());
    }
    public ScanTask cancel(String sourceId, String id) {
        get(sourceId, id);
        if (tasks.cancel(id) != 1) throw conflict("任务已经结束或正在提交索引，无法取消");
        Control control = controls.get(id);
        if (control != null) {
            control.cancel();
            if (executor.remove(control.job)) {
                finish(id, "CANCELLED", "扫描已取消，旧索引已保留"); controls.remove(id);
            }
        }
        return get(sourceId, id);
    }
    private void run(String id, Context context, Control control) {
        control.thread = Thread.currentThread();
        long deadline = System.nanoTime() + Duration.ofHours(2).toNanos();
        try {
            control.check(deadline);
            if (tasks.start(id, Instant.now().toString()) != 1) throw new CancellationException();
            Source source = context.source();
            URI root = URI.create(source.location());
            // Validate configured root using the same decoded-path rules as returned entries.
            WebDavClient.path(root, root.getPath(), root.toASCIIString(), true);
            String authorization = source.username().isEmpty() ? null : "Basic " + Base64.getEncoder().encodeToString(
                    (source.username() + ":" + (context.encrypted() == null ? "" : crypto.decrypt(context.encrypted(), "singverse:webdav:v1")))
                            .getBytes(StandardCharsets.UTF_8));
            tasks.directory(id, AuthCrypto.hash(root.getPath()), root.getPath(), 0);
            Traversal traversal = new Traversal(id, context, root, control, deadline);
            while (true) {
                control.check(deadline);
                var next = tasks.nextDirectory(id);
                if (next.isEmpty()) break;
                var directory = next.getFirst();
                String path = directory.get("path").toString();
                int depth = ((Number) directory.get("depth")).intValue();
                access.request(source.id(), () -> {
                    client.list(root, path, authorization, deadline, () -> control.check(deadline), control::track,
                        entry -> traversal.accept(entry, depth), (status, retry) -> {
                            if(status==401||status==403) access.block(source.id(),true,Instant.now());
                            if(status==429) access.block(source.id(),false,com.mynora.singverse.media.webdav.metadata.WebDavMetadataInput.retryAfter(retry));
                        });
                    return null;
                });
                traversal.directories++;
                traversal.flush();
                tasks.processed(id, directory.get("path_hash").toString());
            }
            traversal.flush(); control.check(deadline);
            transaction.executeWithoutResult(s -> {
                profiles.lockBindings();
                Source current = sources.get(source.id());
                if (!com.mynora.singverse.media.dto.MediaSourceDtos.sameScanConfiguration(current, source) || profiles.savedProfile(current.parserProfileId()).revision() != context.profile().revision())
                    throw conflict("媒体来源或解析方案已修改，本次扫描未保存，请重新扫描");
                if (tasks.committing(id) != 1) throw new CancellationException();
            });
            // Separate transaction makes the COMMITTING phase visible and cancellation unambiguous.
            transaction.executeWithoutResult(s -> {
                profiles.lockBindings();
                Source current = sources.get(source.id());
                if (!com.mynora.singverse.media.dto.MediaSourceDtos.sameScanConfiguration(current, source) || profiles.savedProfile(current.parserProfileId()).revision() != context.profile().revision())
                    throw conflict("媒体来源或解析方案已修改，本次扫描未保存，请重新扫描");
                if (sourceMapper.touch(source.id(), source.updatedAt(), Instant.now().toString()) != 1)
                    throw conflict("媒体来源已修改，本次扫描未保存，请重新扫描");
                metadata.lock();
                embedded.publish(source,id,traversal.total);
                tasks.finish(id, "SUCCEEDED", null, Instant.now().toString());
                tasks.clearSongs(id); tasks.clearDirectories(id);
            });
        } catch (Exception error) {
            Thread.interrupted(); // Return connections and persist terminal state even after cancellation.
            String status = stopping ? "INTERRUPTED" : control.cancelled || error instanceof CancellationException ? "CANCELLED" : "FAILED";
            String detail = stopping ? "服务已停止，扫描中断；请手动重新扫描"
                    : status.equals("CANCELLED") ? "扫描已取消，旧索引已保留"
                    : error instanceof ResponseStatusException response ? response.getReason()
                    : error instanceof IllegalStateException && error.getMessage() != null && error.getMessage().startsWith("WebDAV")
                        ? error.getMessage() : "扫描未完成或保存失败，旧索引已保留，请检查来源后重试";
            finish(id, status, detail);
        } finally { control.track(null); controls.remove(id); Thread.interrupted(); }
    }
    private void finish(String id, String status, String detail) {
        transaction.executeWithoutResult(s -> {
            tasks.finish(id, status, detail, Instant.now().toString()); tasks.clearSongs(id); tasks.clearDirectories(id);
        });
    }
    private final class Traversal {
        final String id; final Context context; final URI root; final Control control; final long deadline;
        final KtvFilenameParser parser = new KtvFilenameParser();
        final String scannedAt = Instant.now().toString();
        final Map<String, MediaSongRepository.Stored> batch = new LinkedHashMap<>();
        final Map<String, WebDavClient.Entry> folders = new LinkedHashMap<>();
        long directories, entries, skipped; int total, matched, review, depth;
        Traversal(String id, Context context, URI root, Control control, long deadline) {
            this.id = id; this.context = context; this.root = root; this.control = control; this.deadline = deadline;
        }
        void accept(WebDavClient.Entry entry, int parentDepth) {
            control.check(deadline);
            if (++entries > 1000000) throw WebDavClient.failure("WebDAV 超过 100 万个目录条目，请拆分来源");
            if (entry.directory()) {
                if (parentDepth >= 128) throw WebDavClient.failure("WebDAV 目录超过 128 层，请拆分来源");
                depth = parentDepth + 1; folders.put(entry.path(), entry);
            } else {
                String relative = entry.path().substring(root.getPath().length());
                String filename = relative.substring(relative.lastIndexOf('/') + 1);
                if (!KtvFilenameParser.supportsMediaFilename(filename)) skipped++;
                else {
                    String songId = UUID.nameUUIDFromBytes((context.source().id() + "\0" + relative).getBytes(StandardCharsets.UTF_8)).toString();
                    var parsed = parser.preview(filename, context.profile().rules());
                    batch.put(songId, new MediaSongRepository.Stored(songId, context.source().id(), relative,
                            AuthCrypto.hash(relative), entry.size(), entry.modifiedAt(), scannedAt,
                            context.profile().id(), context.profile().revision(),
                            parsed.selectedRule() == null ? null : parsed.selectedRule().id(), songs.encode(parsed.result()), parsed.result().needsReview(), null, entry.etag()));
                }
            }
            if (entries % 100 == 0) flush();
        }
        void flush() {
            control.check(deadline);
            transaction.executeWithoutResult(s -> {
                for (String path : folders.keySet()) {
                    String hash = AuthCrypto.hash(path);
                    if (tasks.hasDirectory(id, hash) == 0) tasks.directory(id, hash, path, depth);
                }
                if (!batch.isEmpty()) {
                    Set<String> existing = new HashSet<>(tasks.stagedIds(id, new ArrayList<>(batch.keySet())));
                    List<MediaSongRepository.Stored> added = batch.values().stream().filter(song -> !existing.contains(song.id())).toList();
                    if (total + added.size() > 100000) throw WebDavClient.failure("WebDAV 单来源超过 10 万首媒体文件，请拆分来源");
                    if (!added.isEmpty()) tasks.stage(id, added);
                    total += added.size();
                    for (var song : added) { if (song.ruleId() != null) matched++; if (song.needsReview()) review++; }
                }
                tasks.progress(id, directories, entries, total, matched, review, skipped);
            });
            folders.clear(); batch.clear();
        }
    }
    private static ScanTask task(Map<String, Object> row) {
        return new ScanTask(text(row, "id"), text(row, "source_id"), text(row, "status"), text(row, "phase"),
                number(row, "directories"), number(row, "entries"), (int) number(row, "total_songs"),
                (int) number(row, "matched_songs"), (int) number(row, "review_songs"), number(row, "skipped_files"),
                number(row, "cancel_requested") != 0, text(row, "error"), text(row, "created_at"), text(row, "started_at"), text(row, "finished_at"), (int)number(row,"added_songs"), (int)number(row,"reused_songs"), (int)number(row,"missing_songs"), (int)number(row,"removed_songs"));
    }
    private static String text(Map<String, Object> row, String name) { return (String) row.get(name); }
    private static long number(Map<String, Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }
    @PreDestroy public void stop() {
        stopping = true; maintenance.shutdownNow(); controls.values().forEach(Control::cancel); executor.shutdownNow();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
