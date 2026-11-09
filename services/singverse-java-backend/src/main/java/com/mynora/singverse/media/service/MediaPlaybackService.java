package com.mynora.singverse.media.service;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.transcoding.SongCacheService;
import com.mynora.singverse.media.model.PlaybackFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated original delivery, with an optional service-owned bounded WebDAV block cache. */
@Service
public class MediaPlaybackService {
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final AuthCrypto crypto;
    private final SongCacheService cache;
    private final AsyncTaskExecutor prefetchExecutor;
    private final ObjectMapper json;
    private final Object prefetchLock = new Object();
    private final LinkedHashMap<String, Prefetch> prefetches = new LinkedHashMap<>();
    private final AtomicBoolean prefetchRunning = new AtomicBoolean();
    private volatile java.net.http.HttpClient prefetchHttp;
    private int foreground;
    private long foregroundUntil;
    private boolean stopped;
    public MediaPlaybackService(MediaSongRepository songs, MediaSourceRepository sources, AuthCrypto crypto) {
        this(songs, sources, crypto, null, null, new ObjectMapper());
    }
    @Autowired
    public MediaPlaybackService(MediaSongRepository songs, MediaSourceRepository sources, AuthCrypto crypto,
            SongCacheService cache, @Qualifier("originalPrefetchExecutor") AsyncTaskExecutor prefetchExecutor, ObjectMapper json) {
        this.songs = songs; this.sources = sources; this.crypto = crypto;
        this.cache = cache; this.prefetchExecutor = prefetchExecutor; this.json = json;
    }

    public void play(String id, String range, boolean head, HttpServletResponse response) throws IOException {
        play(id, range, head, response, null);
    }

    /** Internal tasks revalidate their grant while an existing response is streaming. */
    public void play(String id, String range, boolean head, HttpServletResponse response, Runnable validate) throws IOException {
        var song = songs.get(id);
        var source = sources.get(song.sourceId());
        String relative = song.relativePath();
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\")
                || relative.chars().anyMatch(Character::isISOControl)
                || Arrays.stream(relative.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
            throw failure(HttpStatus.BAD_REQUEST, "歌曲路径无效，请重新扫描来源");
        if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))
            throw failure(HttpStatus.BAD_REQUEST, "仅支持单段字节范围");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Disposition", "inline");
        response.setContentType(com.mynora.singverse.media.filename.MediaFileTypes.contentType(relative));
        Runnable guard = validate == null ? () -> {} : () -> {
            validate.run();
            if (source.type().equals("LOCAL")) {
                try {
                    Path root = Path.of(source.location()).toRealPath(), file = root.resolve(relative).toRealPath();
                    var attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!file.startsWith(root) || !attrs.isRegularFile()) throw failure(HttpStatus.FORBIDDEN, "媒体不在来源目录内");
                    if (song.fileSize() != null && attrs.size() != song.fileSize()
                            || song.modifiedAt() != null && !attrs.lastModifiedTime().toInstant().toString().equals(song.modifiedAt()))
                        throw failure(HttpStatus.CONFLICT, "源媒体已变化，请重新扫描来源");
                } catch (IOException error) { throw failure(HttpStatus.CONFLICT, "源媒体已变化或不可读取"); }
            }
        };
        try {
            guard.run();
            if (source.type().equals("LOCAL")) local(source.location(), relative, range, head, response, guard);
            else {
                foregroundStarted();
                try { original(song, source, relative, range, head, response, guard); }
                finally { synchronized (prefetchLock) { foreground--; } }
            }
        } catch (ResponseStatusException error) {
            if (!response.isCommitted()) { response.reset(); response.setHeader("Cache-Control", "no-store"); }
            throw error;
        } catch (IOException error) {
            if (response.isCommitted()) throw error;
            response.reset(); response.setHeader("Cache-Control", "no-store");
            throw failure(source.type().equals("LOCAL") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY, "媒体文件无法读取或连接已中断");
        }
    }

    public void playDerived(Path root, String name, String range, boolean head, HttpServletResponse response) throws IOException {
        if (!name.matches("[a-f0-9-]{36}\\.(?:mp4|m4a|flac)") || range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))
            throw failure(HttpStatus.BAD_REQUEST, "无效的媒体范围");
        response.setContentType(name.endsWith(".flac") ? "audio/flac" : name.endsWith(".m4a") ? "audio/mp4" : "video/mp4");
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        local(root.toString(), name, range, head, response, () -> {});
    }

    private void local(String location, String relative, String range, boolean head, HttpServletResponse response, Runnable validate) throws IOException {
        Path root, file;
        try {
            root = Path.of(location).toRealPath();
            file = root.resolve(relative).toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) throw failure(HttpStatus.NOT_FOUND, "媒体文件不可用，请重新扫描来源");
        } catch (IOException | InvalidPathException error) {
            throw failure(HttpStatus.NOT_FOUND, "媒体文件不存在或无法读取");
        }
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size(), start = 0, end = size - 1;
            if (range != null) {
                try {
                    String[] parts = range.substring(6).split("-", -1);
                    if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); if (suffix <= 0) throw new IllegalArgumentException(); start = Math.max(0, size - suffix); }
                    else { start = Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1])); }
                    if (start >= size || end < start) throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) {
                    response.setStatus(416); response.setHeader("Content-Range", "bytes */" + size); return;
                }
                response.setStatus(206); response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            }
            response.setHeader("Accept-Ranges", "bytes");
            long length = Math.max(0, end - start + 1);
            response.setContentLengthLong(length);
            if (!head) { channel.position(start); copy(Channels.newInputStream(channel), response.getOutputStream(), length, validate); }
        } catch (AccessDeniedException error) { throw failure(HttpStatus.NOT_FOUND, "媒体文件无法读取"); }
    }

    private static final class Remote implements AutoCloseable {
        final HttpURLConnection connection;
        final int status;
        final String contentRange;
        final long length, total;
        Remote(HttpURLConnection connection, int status, String contentRange, long length, long total) {
            this.connection = connection; this.status = status; this.contentRange = contentRange; this.length = length; this.total = total;
        }
        @Override public void close() { connection.disconnect(); }
    }

    private Remote openRemote(Source source, String relative, String range, boolean head, Prefetch prefetch) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI root = URI.create(source.location());
            String rootPath = root.getPath().endsWith("/") ? root.getPath() : root.getPath() + "/";
            URI target = new URI(root.getScheme(), null, root.getHost(), root.getPort(), rootPath + relative, null, null);
            URL url = URI.create(target.toASCIIString()).toURL();
            if (prefetch == null) connection = (HttpURLConnection) url.openConnection();
            else {
                synchronized (prefetchLock) {
                    if (stopped || prefetch.cancelled) throw new java.util.concurrent.CancellationException("Original prefetch stopped");
                    if (prefetchHttp == null) prefetchHttp = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(3)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
                }
                connection = new CancellableWebDavConnection(url, prefetchHttp);
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(prefetch == null ? 10000 : 3000); connection.setReadTimeout(prefetch == null ? 30000 : 5000);
            connection.setRequestMethod(head ? "HEAD" : "GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) connection.setRequestProperty("Range", range);
            var stored = new HashMap<>(sources.find(source.id()));
            String encrypted = (String) stored.get("password_encrypted");
            if (stored.containsKey("location") || encrypted != null || !source.username().isEmpty()) {
                boolean same = source.configurationFingerprint() != null
                        ? source.configurationFingerprint().equals(PlaybackFingerprint.configuration(source.id(), stored))
                        : Objects.equals(source.location(), stored.get("location")) && Objects.equals(source.username(), stored.get("username"))
                                && Objects.equals(source.type(), stored.get("source_type")) && Objects.equals(source.updatedAt(), stored.get("updated_at"));
                if (!same)
                    throw failure(HttpStatus.CONFLICT, "来源配置已变化，请重新播放");
            }
            if (!source.username().isEmpty() || encrypted != null) {
                String password = encrypted == null ? "" : crypto.decrypt(encrypted, "singverse:webdav:v1");
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (source.username() + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            if (prefetch != null) { prefetch.connection = connection; prefetch.check(); }
            int status = connection.getResponseCode();
            if (status == 416) {
                String value = connection.getHeaderField("Content-Range");
                return new Remote(connection, status, value != null && value.matches("bytes \\*/[0-9]+") ? value : null, 0, -1);
            }
            if (status != 200 && status != 206) {
                if (prefetch != null && Set.of(400, 401, 403, 404, 409, 410).contains(status))
                    throw failure(HttpStatus.valueOf(status), "WebDAV 原片预取不可用");
                throw failure(HttpStatus.BAD_GATEWAY, status >= 300 && status < 400
                        ? "WebDAV 媒体请求发生重定向，请使用支持直接读取的 WebDAV 地址" : "WebDAV 媒体读取失败，请检查地址、凭据和文件权限");
            }
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 返回压缩的媒体响应，无法安全读取字节范围");
            // An ignored nonzero range makes players/ffprobe download and discard the entire
            // prefix on every seek. Fail before exposing bytes instead of stalling playback.
            if (!head && status == 200 && range != null && !range.matches("bytes=0+-[0-9]*"))
                throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 不支持媒体字节范围，无法定位播放，请检查来源设置");
            String contentRange = connection.getHeaderField("Content-Range");
            long length = connection.getContentLengthLong();
            if (status == 206) {
                long expected = remoteRangeLength(range, contentRange);
                if (length >= 0 && length != expected)
                    throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 返回不一致的媒体长度");
                length = expected;
            }
            long total = status == 206 ? Long.parseLong(contentRange.substring(contentRange.lastIndexOf('/') + 1)) : length;
            return new Remote(connection, status, contentRange, length, total);
        } catch (URISyntaxException | IllegalArgumentException error) {
            if (connection != null) connection.disconnect();
            throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 媒体地址无效");
        } catch (IOException | RuntimeException error) {
            if (connection != null) connection.disconnect();
            throw error;
        }
    }

    private static void remoteHeaders(Remote remote, HttpServletResponse response) {
        response.setStatus(remote.status);
        if (remote.contentRange != null) response.setHeader("Content-Range", remote.contentRange);
        if ("bytes".equalsIgnoreCase(remote.connection.getHeaderField("Accept-Ranges")) || remote.status == 206)
            response.setHeader("Accept-Ranges", "bytes");
        if (remote.length >= 0) response.setContentLengthLong(remote.length);
    }

    private String fingerprint(Song song, Source source) {
        return PlaybackFingerprint.of(song, source);
    }
    private Runnable stable(Song song, Source source, Runnable guard) {
        String expected = fingerprint(song, source); long[] next = {0};
        return () -> {
            guard.run();
            if (System.nanoTime() < next[0]) return;
            next[0] = System.nanoTime() + 1_000_000_000L;
            var current = songs.get(song.id());
            if (!expected.equals(fingerprint(current, sources.get(current.sourceId()))))
                throw failure(HttpStatus.CONFLICT, "源媒体或来源配置已变化，请重新播放");
        };
    }
    private SongCacheService.Original cached(Song song, Source source, long total, boolean create) {
        if (cache == null) return null;
        try { return cache.original(song.id(), fingerprint(song, source), total, create); }
        catch (RuntimeException unavailable) { return null; } // An optional cache must not make a readable original unavailable.
    }

    private void original(Song song, Source source, String relative, String range, boolean head,
            HttpServletResponse response, Runnable guard) throws IOException {
        long indexedSize = song.fileSize() == null ? -1 : song.fileSize();
        var local = cached(song, source, indexedSize, false);
        if (local == null && !head && indexedSize > 0 && cacheableRange(range, indexedSize))
            local = cached(song, source, indexedSize, true);
        if (local != null) {
            if (local.partialUsable()) {
                try (var selected = local) { mixed(selected, source, relative, range, head, response, stable(song, source, guard)); }
                return;
            }
            local.close();
        }
        try (var remote = openRemote(source, relative, range, head, null)) {
            remoteHeaders(remote, response);
            if (head || remote.status == 416) return;
            long start = remote.status == 206 ? Long.parseLong(remote.contentRange.substring(6, remote.contentRange.indexOf('-'))) : 0;
            var discovered = remote.total > 0 ? cached(song, source, remote.total, false) : null;
            if (discovered == null && remote.total > 0 && cacheableRange(start, remote.length, remote.total))
                discovered = cached(song, source, remote.total, true);
            if (discovered != null && (remote.status == 206 || "bytes".equalsIgnoreCase(remote.connection.getHeaderField("Accept-Ranges")))) markRanges(discovered);
            try (var selected = discovered; var input = remote.connection.getInputStream()) {
                tee(input, response.getOutputStream(), remote.length, selected, start, selected == null ? guard : stable(song, source, guard), false);
            }
        }
    }

    private void mixed(SongCacheService.Original original, Source source, String relative, String range, boolean head,
            HttpServletResponse response, Runnable guard) throws IOException {
        long total = original.sourceBytes(), start = 0, end = total - 1;
        if (range != null) {
            try {
                String[] parts = range.substring(6).split("-", -1);
                if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); if (suffix <= 0) throw new IllegalArgumentException(); start = Math.max(0, total - suffix); }
                else { start = Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1])); }
                if (start >= total || end < start) throw new IllegalArgumentException();
            } catch (IllegalArgumentException badRange) { response.setStatus(416); response.setHeader("Content-Range", "bytes */" + total); return; }
            response.setStatus(206); response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + total);
        }
        response.setHeader("Accept-Ranges", "bytes"); response.setContentLengthLong(end - start + 1);
        if (head) return;
        var output = response.getOutputStream();
        while (start <= end) {
            guard.run();
            byte[] cachedBytes = null;
            if (cachePresent(original, start)) {
                long last = Math.min(end, original.blockEnd(start));
                try { cachedBytes = original.read(start, last, guard); }
                catch (IOException failure) { /* Read the same interval from its authorized source. */ }
                catch (RuntimeException failure) {
                    if (failure instanceof java.util.concurrent.CancellationException
                            || failure instanceof ResponseStatusException status && status.getStatusCode().value() != 500 && status.getStatusCode().value() != 507) throw failure;
                }
            }
            if (cachedBytes != null) {
                output.write(cachedBytes); start += cachedBytes.length;
                response.flushBuffer(); // A cached prefix reaches the player before connecting for a missing suffix.
            } else {
                long last; try { last = original.missingEnd(start, end); } catch (RuntimeException unavailable) { last = end; }
                String request = "bytes=" + start + "-" + last;
                try (var remote = openRemote(source, relative, request, false, null)) {
                    if (remote.status == 200 && start == 0 && remote.total < 0 && !response.isCommitted()) {
                        response.setHeader("Content-Range", null); response.setContentLengthLong(-1); response.setHeader("Content-Length", null);
                        remoteHeaders(remote, response);
                        try (var input = remote.connection.getInputStream()) { tee(input, output, -1, null, 0, guard, false); }
                        return;
                    }
                    if (remote.status == 416 || remote.total != total) throw failure(HttpStatus.CONFLICT, "源媒体长度已变化，请重新扫描来源");
                    if (remote.status == 206 || "bytes".equalsIgnoreCase(remote.connection.getHeaderField("Accept-Ranges"))) markRanges(original);
                    try (var input = remote.connection.getInputStream()) { tee(input, output, last - start + 1, original, start, guard, false); }
                }
                start = last + 1;
            }
        }
    }
    private static boolean cachePresent(SongCacheService.Original original, long offset) {
        try { return original.present(offset); } catch (RuntimeException unavailable) { return false; }
    }
    private static void markRanges(SongCacheService.Original original) {
        try { original.rangeSupported(); } catch (RuntimeException unavailable) { /* Optional cache metadata cannot block valid source bytes. */ }
    }

    private static boolean cacheableRange(long start, long length, long total) {
        if (length <= 0 || total <= 0 || start < 0) return false;
        long first = (start + SongCacheService.ORIGINAL_BLOCK_BYTES - 1) / SongCacheService.ORIGINAL_BLOCK_BYTES * SongCacheService.ORIGINAL_BLOCK_BYTES;
        long end = start + length;
        return end == total ? first < end : end / SongCacheService.ORIGINAL_BLOCK_BYTES * SongCacheService.ORIGINAL_BLOCK_BYTES > first;
    }
    private static boolean cacheableRange(String range, long total) {
        if (range == null) return true;
        try {
            String[] parts = range.substring(6).split("-", -1);
            long start = parts[0].isEmpty() ? Math.max(0, total - Long.parseLong(parts[1])) : Long.parseLong(parts[0]);
            long end = parts[0].isEmpty() || parts[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(parts[1]));
            return cacheableRange(start, end - start + 1, total);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private void tee(InputStream input, OutputStream output, long remaining, SongCacheService.Original original,
            long start, Runnable guard, boolean strictCache) throws IOException {
        SongCacheService.OriginalWriter writer = null;
        if (original != null && remaining >= 0) {
            try { writer = original.writer(start, start + remaining - 1, guard); }
            catch (RuntimeException failure) { if (strictCache) throw failure; }
        }
        try {
            if (strictCache && (writer == null || writer.capacityExhausted())) throw failure(HttpStatus.INSUFFICIENT_STORAGE, "原片缓存容量不足");
            byte[] buffer = new byte[65536]; long nextCheck = 0, position = start;
            while (remaining != 0) {
                if (strictCache) {
                    boolean limited; synchronized (prefetchLock) { limited = foreground > 0; }
                    if (limited) java.util.concurrent.locks.LockSupport.parkNanos(250_000_000L); // At most 256 KiB/s while a foreground response remains open.
                }
                if (System.nanoTime() >= nextCheck) { guard.run(); nextCheck = System.nanoTime() + 250_000_000L; }
                int limit = remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining);
                // Publish a completed cache block before the player receives bytes from the next one.
                if (writer != null) limit = (int) Math.min(limit, original.blockEnd(position) - position + 1);
                int count = input.read(buffer, 0, limit);
                if (count < 0) { if (remaining > 0) throw new EOFException("Incomplete original media"); break; }
                if (count == 0) continue;
                if (System.nanoTime() >= nextCheck) { guard.run(); nextCheck = System.nanoTime() + 250_000_000L; }
                output.write(buffer, 0, count);
                if (writer != null) {
                    try { writer.write(buffer, 0, count); }
                    catch (IOException | RuntimeException failure) {
                        writer.close(); writer = null;
                        if (strictCache || failure instanceof java.util.concurrent.CancellationException
                                || failure instanceof ResponseStatusException status && status.getStatusCode().value() != 500 && status.getStatusCode().value() != 507)
                            throw failure;
                    }
                    if (strictCache && remaining > count && writer != null && writer.capacityExhausted())
                        throw failure(HttpStatus.INSUFFICIENT_STORAGE, "原片缓存容量不足");
                }
                position += count;
                if (remaining > 0) remaining -= count;
            }
            guard.run();
        } finally { if (writer != null) writer.close(); }
    }

    private static final class Prefetch {
        final String songId, owner;
        volatile BooleanSupplier active;
        volatile boolean cancelled, paused, capacityWaiting;
        volatile HttpURLConnection connection;
        volatile String state = "QUEUED", error = "";
        volatile long cachedBytes, sourceBytes, retryAt, capacityGeneration;
        int failures;
        Prefetch(String songId, String owner, BooleanSupplier active) { this.songId = songId; this.owner = owner; this.active = active; }
        void check() {
            if (cancelled || paused || !active.getAsBoolean()) throw new java.util.concurrent.CancellationException("Original prefetch stopped");
        }
        void stop(boolean cancel) {
            if (cancel) { cancelled = true; state = "CANCELLED"; } else paused = true;
            var current = connection; if (current != null) current.disconnect();
        }
    }

    /** Registration is memory-only, including when called from a queue transaction's afterCommit. */
    public void prefetchOriginal(String songId, String owner, BooleanSupplier active) {
        if (owner == null || owner.isBlank() || owner.length() > 160 || songId == null || active == null)
            throw failure(HttpStatus.BAD_REQUEST, "原片预取参数无效");
        Prefetch previous = null;
        synchronized (prefetchLock) {
            if (stopped) return;
            var existing = prefetches.get(owner);
            if (existing != null && existing.songId.equals(songId) && !existing.cancelled) { existing.active = active; return; }
            previous = prefetches.remove(owner);
            prefetches.put(owner, new Prefetch(songId, owner, active));
            cache.protectPrefetch(owner, songId);
            if (prefetches.size() > 16) {
                var iterator = prefetches.entrySet().iterator(); var eldest = iterator.next().getValue(); iterator.remove(); eldest.stop(true); cache.protectPrefetch(eldest.owner, null);
            }
        }
        if (previous != null) previous.stop(true);
    }
    public void cancelOriginalPrefetch(String owner) {
        Prefetch task;
        synchronized (prefetchLock) {
            task = prefetches.get(owner);
            // Invalidate before registration can reuse this owner/song. Socket close stays outside the lock.
            if (task != null) { task.cancelled = true; task.state = "CANCELLED"; }
            cache.protectPrefetch(owner, null);
        }
        if (task != null) task.stop(true);
    }
    public ObjectNode originalPrefetchStatus(String owner) {
        synchronized (prefetchLock) {
            var task = prefetches.get(owner);
            if (task == null) return json.createObjectNode().put("state", "IDLE");
            return json.createObjectNode().put("state", task.state).put("songId", task.songId)
                    .put("cachedBytes", task.cachedBytes).put("sourceBytes", task.sourceBytes).put("error", task.error);
        }
    }
    private void foregroundStarted() {
        List<Prefetch> tasks;
        synchronized (prefetchLock) { foreground++; foregroundUntil = System.nanoTime() + 2_000_000_000L; tasks = new ArrayList<>(prefetches.values()); }
        // A live player takes priority over speculative bandwidth, including a stalled prefetch body.
        tasks.forEach(task -> task.stop(false));
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 2000, scheduler = "transcodeScheduler")
    public void prefetchTick() {
        synchronized (prefetchLock) { if (stopped || System.nanoTime() < foregroundUntil || prefetchExecutor == null || !prefetchRunning.compareAndSet(false, true)) return; }
        try { prefetchExecutor.execute(() -> { try { runPrefetch(); } finally { prefetchRunning.set(false); } }); }
        catch (org.springframework.core.task.TaskRejectedException shutdown) { prefetchRunning.set(false); }
    }
    private void runPrefetch() {
        Prefetch task = null;
        synchronized (prefetchLock) {
            if (stopped || System.nanoTime() < foregroundUntil) return;
            for (var candidate : prefetches.values()) {
                if (candidate.cancelled || !candidate.active.getAsBoolean()) { candidate.stop(true); cache.protectPrefetch(candidate.owner, null); continue; }
                if (Set.of("COMPLETE", "NOT_REMOTE").contains(candidate.state)) continue;
                if (candidate.retryAt > System.currentTimeMillis()
                        && (!candidate.capacityWaiting || candidate.capacityGeneration == cache.cleanupGeneration())) continue;
                task = candidate; break;
            }
            if (task == null) return;
            task.paused = false; task.state = "FETCHING"; task.error = "";
        }
        try {
            var song = songs.get(task.songId); var source = sources.get(song.sourceId());
            if (!source.type().equals("WEBDAV")) { task.state = "NOT_REMOTE"; return; }
            String relative = song.relativePath();
            if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\")
                    || relative.chars().anyMatch(Character::isISOControl)
                    || Arrays.stream(relative.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
                throw failure(HttpStatus.BAD_REQUEST, "歌曲路径无效");
            task.check();
            long size = song.fileSize() == null ? -1 : song.fileSize();
            var original = cached(song, source, size, false);
            if (original == null && size <= 0) try (var remote = openRemote(source, relative, null, true, task)) { size = remote.total; }
            if (original == null) original = cached(song, source, size, true);
            if (original == null) throw failure(size > 0 ? HttpStatus.INSUFFICIENT_STORAGE : HttpStatus.SERVICE_UNAVAILABLE, "原片长度未知或缓存暂不可用");
            task.capacityWaiting = false;
            final var selected = task;
            try (var entry = original) {
                selected.sourceBytes = entry.sourceBytes();
                Runnable guard = stable(song, source, selected::check);
                // Header and trailer contain common container indexes, including MP4's tail moov.
                fetchOriginal(entry, source, relative, 0, Math.min(entry.sourceBytes() - 1, SongCacheService.ORIGINAL_BLOCK_BYTES - 1), selected, guard);
                long tail = (entry.sourceBytes() - 1) / SongCacheService.ORIGINAL_BLOCK_BYTES * SongCacheService.ORIGINAL_BLOCK_BYTES;
                fetchOriginal(entry, source, relative, tail, entry.sourceBytes() - 1, selected, guard);
                for (long offset = 0; offset < entry.sourceBytes();) {
                    selected.check();
                    if (entry.present(offset)) { offset = entry.blockEnd(offset) + 1; continue; }
                    long end = entry.missingEnd(offset, entry.sourceBytes() - 1);
                    fetchOriginal(entry, source, relative, offset, end, selected, guard); offset = end + 1;
                }
                selected.cachedBytes = entry.cachedBytes();
                selected.check(); selected.state = selected.cachedBytes == selected.sourceBytes ? "COMPLETE" : "PARTIAL";
            }
        } catch (java.util.concurrent.CancellationException cancelled) {
            if (!task.cancelled) { task.state = "PARTIAL"; task.retryAt = System.currentTimeMillis() + 1000; }
        } catch (IOException | RuntimeException failure) {
            if (task.cancelled) task.state = "CANCELLED";
            else if (task.paused) { task.state = "PARTIAL"; task.retryAt = System.currentTimeMillis() + 1000; }
            else if (failure instanceof ResponseStatusException status && status.getStatusCode().value() == 507) {
                task.capacityWaiting = true; task.capacityGeneration = cache.cleanupGeneration();
                task.state = "PARTIAL"; task.retryAt = System.currentTimeMillis() + 30_000;
                task.error = "等待缓存空间释放，播放时仍可直接回源";
            } else {
                task.state = "FAILED"; task.failures++;
                boolean permanent = failure instanceof ResponseStatusException status
                        && Set.of(400, 401, 403, 404, 409, 410).contains(status.getStatusCode().value());
                task.retryAt = permanent || task.failures >= 3 ? Long.MAX_VALUE : System.currentTimeMillis() + task.failures * 5000L;
                task.error = task.retryAt == Long.MAX_VALUE ? "原片预取已停止，播放时仍可直接回源" : "原片预取未完成，将稍后重试";
            }
        } finally { task.connection = null; }
    }
    private void fetchOriginal(SongCacheService.Original original, Source source, String relative, long start, long end,
            Prefetch task, Runnable guard) throws IOException {
        if (original.present(start)) { task.cachedBytes = original.cachedBytes(); return; }
        guard.run();
        try (var remote = openRemote(source, relative, "bytes=" + start + "-" + end, false, task)) {
            if (remote.status == 416 || remote.total != original.sourceBytes()) throw failure(HttpStatus.CONFLICT, "源媒体长度已变化");
            if (remote.status == 206 || "bytes".equalsIgnoreCase(remote.connection.getHeaderField("Accept-Ranges"))) original.rangeSupported();
            try (var input = remote.connection.getInputStream()) { tee(input, OutputStream.nullOutputStream(), end - start + 1, original, start, guard, true); }
        } finally { task.connection = null; task.cachedBytes = original.cachedBytes(); }
    }
    @jakarta.annotation.PreDestroy
    public void closePrefetch() {
        List<Prefetch> tasks;
        synchronized (prefetchLock) { stopped = true; tasks = new ArrayList<>(prefetches.values()); }
        tasks.forEach(task -> { task.stop(true); cache.protectPrefetch(task.owner, null); });
        var client = prefetchHttp; if (client != null) client.shutdownNow();
    }

    private static long remoteRangeLength(String requested, String returned) {
        try {
            if (requested == null || returned == null || !returned.matches("bytes [0-9]+-[0-9]+/[0-9]+"))
                throw new IllegalArgumentException();
            String[] actual = returned.substring(6).split("[-/]");
            long start = Long.parseLong(actual[0]), end = Long.parseLong(actual[1]), size = Long.parseLong(actual[2]);
            if (size <= 0 || start > end || end >= size) throw new IllegalArgumentException();
            String[] parts = requested.substring(6).split("-", -1);
            long expectedStart, expectedEnd = size - 1;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) throw new IllegalArgumentException();
                expectedStart = Math.max(0, size - suffix);
            } else {
                expectedStart = Long.parseLong(parts[0]);
                if (!parts[1].isEmpty()) expectedEnd = Math.min(expectedEnd, Long.parseLong(parts[1]));
            }
            if (start != expectedStart || end != expectedEnd) throw new IllegalArgumentException();
            return end - start + 1;
        } catch (IllegalArgumentException error) {
            throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 返回无效的媒体范围");
        }
    }

    private static void copy(InputStream input, OutputStream output, long remaining, Runnable validate) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long nextCheck = 0;
        while (remaining != 0) {
            if (System.nanoTime() >= nextCheck) { validate.run(); nextCheck = System.nanoTime() + 250_000_000L; }
            int count = input.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (count < 0) { if (remaining > 0) throw new EOFException("Incomplete media response"); break; }
            if (System.nanoTime() >= nextCheck) { validate.run(); nextCheck = System.nanoTime() + 250_000_000L; }
            output.write(buffer, 0, count); if (remaining > 0) remaining -= count;
        }
    }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
