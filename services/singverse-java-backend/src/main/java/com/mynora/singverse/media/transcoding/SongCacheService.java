package com.mynora.singverse.media.transcoding;

import static com.mynora.singverse.media.transcoding.TranscodeClient.fail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.metadata.MetadataService;
import com.mynora.singverse.media.repository.MediaSongRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Durable cache belongs to the business server; compute owns only temporary execution files. */
@Service
public class SongCacheService {
    private static final long LEASE = 120_000;
    private static final String OWNER_FILE = ".singverse-cache-owner";
    private static final String OWNER = "SingVerse song cache v1\n";
    private static final Pattern ARTIFACT = Pattern.compile("master\\.m3u8|stream_[a-zA-Z0-9]+\\.m3u8|stream_[a-zA-Z0-9]+_[0-9]+\\.(?:ts|m4s)|init_[a-zA-Z0-9]+\\.mp4|subtitle\\.vtt");
    private static final Set<String> SETTINGS_FIELDS = Set.of("maxBytes", "policy", "ttlHours", "revision");
    private final Object lock = new Object();
    private final TranscodeClient compute;
    private final SongCacheMapper mapper;
    private final MediaSongRepository songs;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Path directory;
    private final long defaultBytes;
    private final long minimumFreeBytes;
    private final Map<Path, Long> reservations = new HashMap<>();
    private final Map<String, Integer> readers = new HashMap<>();

    public SongCacheService(TranscodeClient compute, SongCacheMapper mapper, MediaSongRepository songs,
            ObjectMapper json, PlatformTransactionManager transactions,
            @Value("${singverse.transcoding.cache-directory:${singverse.transcoding.directory:./data/transcoding}/cache}") String directory,
            @Value("${singverse.transcoding.cache-bytes:10737418240}") long defaultBytes,
            @Value("${singverse.transcoding.cache-minimum-free-bytes:2147483648}") long minimumFreeBytes) {
        this.compute = compute; this.mapper = mapper; this.songs = songs; this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.defaultBytes = defaultBytes; this.minimumFreeBytes = minimumFreeBytes;
        if (defaultBytes < 64L * 1024 * 1024 || defaultBytes > (1L << 50) || minimumFreeBytes < 0)
            throw new IllegalArgumentException("Invalid song cache deployment budget");
    }

    private static long now() { return System.currentTimeMillis(); }
    private static String text(Map<String, Object> row, String field) { return Objects.toString(row.get(field), ""); }
    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    private static boolean uuid(String value) { return value != null && value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"); }
    private ObjectNode decode(Object value) {
        try { return (ObjectNode) json.readTree(value.toString()); }
        catch (Exception error) { throw fail(500, "歌曲缓存索引无效"); }
    }
    private ObjectNode settings() {
        mapper.initialize(defaultBytes);
        var row = mapper.settings();
        return json.createObjectNode().put("maxBytes", number(row, "max_bytes"))
                .put("policy", text(row, "policy")).put("ttlHours", number(row, "ttl_hours"))
                .put("revision", number(row, "revision"));
    }
    private void ensureDirectory() {
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        } catch (IOException error) { throw fail(507, "歌曲缓存目录不可写或空间不足"); }
    }
    private Path folder(String id) {
        if (!uuid(id)) throw fail(404, "缓存不存在");
        return directory.resolve(id);
    }
    private long size(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return 0;
        try (var paths = Files.walk(path)) {
            long total = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) { var file = iterator.next(); if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(file)); }
            return total;
        } catch (IOException | ArithmeticException error) { throw fail(500, "无法读取歌曲缓存用量"); }
    }
    private long usage() { return size(directory); }
    private long committedUsage() {
        long total = reservations.values().stream().mapToLong(Long::longValue).sum();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return total;
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) if (!reservations.containsKey(path)) total += size(path);
            return total;
        } catch (IOException error) { throw fail(500, "无法读取歌曲缓存预留容量"); }
    }
    private boolean leased(String id) { return readers.getOrDefault(id, 0) > 0 || mapper.active(id, now() - LEASE) > 0; }
    private boolean owned(Path folder) {
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return false;
        var marker = folder.resolve(OWNER_FILE);
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != OWNER.length()) return false;
            try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
                return OWNER.equals(new String(input.readNBytes(OWNER.length() + 1), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException error) { return false; }
    }
    private boolean valid(Map<String, Object> row) {
        if (row == null || Files.isSymbolicLink(directory)) return false;
        var folder = folder(text(row, "id"));
        if (!owned(folder)) return false;
        var artifacts = decode(row.get("artifacts_json"));
        var fields = artifacts.fields();
        try {
            while (fields.hasNext()) {
                var entry = fields.next();
                var file = folder.resolve(entry.getKey());
                if (!ARTIFACT.matcher(entry.getKey()).matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) != entry.getValue().path("size").asLong(-1)) return false;
            }
            return artifacts.has("master.m3u8");
        } catch (IOException error) { return false; }
    }
    private boolean expired(Map<String, Object> row, ObjectNode config) {
        return config.path("policy").asText().equals("TTL") && now() - number(row, "created_at") >= config.path("ttlHours").asLong() * 3_600_000;
    }

    /** Stable content and output intent; task references and node origins never enter this key. */
    public String key(String songId, String fingerprint, JsonNode requestTarget, JsonNode client, boolean force, boolean explicitCodec) {
        var input = json.createObjectNode().put("version", 1).put("songId", songId).put("fingerprint", fingerprint)
                .put("force", force).put("explicitCodec", explicitCodec);
        input.set("target", requestTarget); input.set("client", client);
        return com.mynora.singverse.auth.crypto.AuthCrypto.hash(canonical(input).toString());
    }
    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isMissingNode()) return json.nullNode();
        if (node.isObject()) {
            var out = json.createObjectNode(); var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            for (var name : names) out.set(name, canonical(node.get(name)));
            return out;
        }
        if (node.isArray()) { var out = json.createArrayNode(); node.forEach(value -> out.add(canonical(value))); return out; }
        return node;
    }
    private ObjectNode snapshot(Map<String, Object> row, boolean hit) {
        return decode(row.get("execution_json")).put("cacheId", text(row, "id")).put("cacheHit", hit)
                .put("state", "SUCCEEDED").put("ready", true);
    }
    private void bind(String session, Map<String, Object> row, boolean hit) {
        if (!uuid(session)) throw fail(400, "播放会话无效");
        var lease = mapper.lease(session);
        if (lease != null && !text(lease, "cache_id").equals(text(row, "id"))) throw fail(409, "播放会话已绑定其他缓存");
        if (lease == null) mapper.bind(session, text(row, "id"), now()); else mapper.renew(session, now());
        if (hit && lease == null) mapper.hit(text(row, "id"), now()); else mapper.touch(text(row, "id"), now());
    }
    public ObjectNode acquire(String key, String sessionId) {
        synchronized (lock) {
            var row = mapper.byKey(key);
            if (!valid(row) || expired(row, settings())) return null;
            transaction.executeWithoutResult(status -> bind(sessionId, row, true));
            return snapshot(row, true);
        }
    }
    public boolean contains(String cacheId) { synchronized (lock) { return uuid(cacheId) && valid(mapper.find(cacheId)); } }
    public ObjectNode renew(String sessionId) {
        synchronized (lock) {
            var lease = mapper.lease(sessionId);
            if (lease == null) return null;
            var row = mapper.find(text(lease, "cache_id"));
            if (!valid(row)) return null;
            mapper.renew(sessionId, now()); mapper.touch(text(row, "id"), now());
            return snapshot(row, false);
        }
    }
    public void release(String sessionId) { synchronized (lock) { mapper.release(sessionId); } }

    public JsonNode list(int page, int pageSize) {
        if (page < 0 || page > 1_000_000 || pageSize < 1 || pageSize > 100) throw fail(400, "缓存分页参数无效");
        synchronized (lock) {
            var entries = mapper.entries();
            var out = json.createObjectNode().put("usageBytes", usage()).put("entryCount", entries.size())
                    .put("page", page).put("size", pageSize).put("totalElements", entries.size());
            out.set("settings", settings()); var items = out.putArray("items");
            long start = (long) page * pageSize;
            for (long index = start; index < Math.min(entries.size(), start + pageSize); index++) {
                var row = entries.get((int) index);
                var item = items.addObject().put("id", text(row, "id")).put("songId", text(row, "song_id"))
                        .put("mediaType", text(row, "media_type")).put("bytes", number(row, "bytes"))
                        .put("createdAt", Instant.ofEpochMilli(number(row, "created_at")).toString())
                        .put("lastAccessAt", Instant.ofEpochMilli(number(row, "last_access_at")).toString())
                        .put("hits", number(row, "hits")).put("leased", leased(text(row, "id")));
                item.set("target", decode(row.get("target_json")));
                try {
                    var song = songs.get(text(row, "song_id"));
                    item.put("title", MetadataService.title(song)).put("artist", String.join(" / ", MetadataService.artists(song)));
                } catch (ResponseStatusException missing) { if (missing.getStatusCode().value() != 404) throw missing; }
            }
            return out;
        }
    }
    public JsonNode save(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != SETTINGS_FIELDS.size()) throw fail(400, "请提交完整缓存设置");
        body.fieldNames().forEachRemaining(name -> { if (!SETTINGS_FIELDS.contains(name)) throw fail(400, "未知缓存设置"); });
        long maximum = integer(body, "maxBytes"), ttl = integer(body, "ttlHours"), revision = integer(body, "revision");
        if (maximum < 64L * 1024 * 1024 || maximum > (1L << 50) || ttl < 1 || ttl > 8760 || revision < 0
                || !body.path("policy").isTextual() || !Set.of("LRU", "LFU", "TTL", "MANUAL").contains(body.path("policy").asText()))
            throw fail(400, "缓存容量、保留时间或清理策略无效");
        synchronized (lock) {
            settings();
            if (mapper.saveSettings(Map.of("maxBytes", maximum, "policy", body.path("policy").asText(), "ttlHours", ttl, "revision", revision)) != 1)
                throw fail(409, "缓存设置已变化，请重新读取");
            // Settings commit is complete; a cleanup failure must not imply it was rolled back.
            try { trim(0); } catch (ResponseStatusException failure) { logCleanup(failure); }
            return settings();
        }
    }
    private static long integer(JsonNode body, String key) {
        var value = body.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw fail(400, "缓存设置必须使用整数");
        return value.longValue();
    }
    public void delete(String id) {
        synchronized (lock) {
            var row = uuid(id) ? mapper.find(id) : null;
            if (row == null) throw fail(404, "缓存不存在");
            if (leased(id)) throw fail(409, "缓存正在播放，请稍后重试");
            evict(row);
        }
    }
    public JsonNode clear() {
        synchronized (lock) {
            int removed = 0, skipped = 0;
            for (var row : mapper.entries()) {
                if (leased(text(row, "id"))) skipped++; else { evict(row); removed++; }
            }
            cleanupPartial();
            return json.createObjectNode().put("removed", removed).put("skipped", skipped);
        }
    }
    private void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) { Files.delete(root); return; }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    private void evict(Map<String, Object> row) {
        Path folder = folder(text(row, "id"));
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !owned(folder)) throw fail(500, "缓存目录所有权标记无效，请核对缓存目录配置");
        try { removeTree(folder); }
        catch (IOException error) { throw fail(500, "缓存清理失败，请检查目录权限后重试"); }
        transaction.executeWithoutResult(status -> { mapper.releaseCache(text(row, "id")); mapper.delete(text(row, "id")); });
    }
    private void cleanupPartial() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!reservations.containsKey(path) && owned(path) && (name.matches("\\.partial-[a-f0-9-]{36}")
                        || uuid(name) && mapper.find(name) == null)) removeTree(path);
            }
        } catch (IOException error) { throw fail(500, "临时缓存清理失败，请检查目录权限"); }
    }
    private void trim(long needed) {
        var config = settings();
        var candidates = new ArrayList<Map<String, Object>>();
        for (var row : mapper.entries()) if (!leased(text(row, "id"))) candidates.add(row);
        if (config.path("policy").asText().equals("MANUAL")) return;
        for (var row : new ArrayList<>(candidates)) if (expired(row, config)) { evict(row); candidates.remove(row); }
        Comparator<Map<String, Object>> order = Comparator.comparingLong(row -> number(row, "last_access_at"));
        if (config.path("policy").asText().equals("LFU")) order = Comparator.<Map<String, Object>>comparingLong(row -> number(row, "hits")).thenComparing(order);
        candidates.sort(order.thenComparing(row -> text(row, "id")));
        for (var row : candidates) {
            if (committedUsage() + needed <= config.path("maxBytes").asLong() && enoughDisk(needed)) break;
            evict(row);
        }
    }
    private boolean enoughDisk(long needed) {
        try { return Files.getFileStore(directory).getUsableSpace() - needed >= minimumFreeBytes; }
        catch (IOException error) { throw fail(507, "无法读取歌曲缓存可用磁盘空间"); }
    }
    public void assertCapacity() {
        synchronized (lock) { ensureDirectory(); trim(1); if (committedUsage() >= settings().path("maxBytes").asLong() || !enoughDisk(1)) throw fail(507, "歌曲缓存容量或磁盘空间不足，请清理缓存"); }
    }
    @Scheduled(fixedDelay = 10000, initialDelay = 10000, scheduler = "transcodeScheduler")
    public void maintain() {
        synchronized (lock) {
            try { mapper.expire(now() - LEASE); ensureDirectory(); cleanupPartial(); trim(0); }
            catch (ResponseStatusException failure) { logCleanup(failure); }
        }
    }
    private void logCleanup(Exception failure) { LoggerFactory.getLogger(SongCacheService.class).warn("Song cache cleanup could not complete; retained cache index for retry"); }

    /** Download outside the registry lock, verify every byte, then atomically publish and bind. */
    public ObjectNode capture(Map<String, Object> row, String key, Connection connection, JsonNode execution) {
        String session = text(row, "id");
        if (!uuid(session) || !execution.path("state").asText().equals("SUCCEEDED")) throw fail(409, "缓存任务尚未完成");
        synchronized (lock) {
            var prior = mapper.byKey(key);
            if (valid(prior) && !expired(prior, settings())) { transaction.executeWithoutResult(status -> bind(session, prior, false)); return snapshot(prior, false); }
        }
        String remote = text(row, "execution_id"); if (remote.isBlank()) remote = session;
        if (!uuid(remote)) throw fail(400, "缓存执行引用无效");
        var exported = compute.call(connection, "/internal/v1/transcoding/executions/" + remote + "/export", "GET", null);
        var artifacts = exported.path("artifacts");
        long total = validateExport(artifacts);
        long reserved = Math.addExact(total, OWNER.length());
        String id = UUID.randomUUID().toString(); Path staging = directory.resolve(".partial-" + id);
        synchronized (lock) {
            ensureDirectory(); trim(reserved);
            if (reserved > settings().path("maxBytes").asLong() - committedUsage() || !enoughDisk(reserved)) throw fail(507, "歌曲缓存容量或磁盘空间不足");
            try {
                Files.createDirectory(staging);
                Files.writeString(staging.resolve(OWNER_FILE), OWNER, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException error) { try { removeTree(staging); } catch (IOException ignored) {} throw fail(507, "无法创建临时歌曲缓存"); }
            reservations.put(staging, reserved);
        }
        try {
            var fields = artifacts.fields();
            while (fields.hasNext()) {
                var artifact = fields.next();
                download(connection, remote, artifact.getKey(), artifact.getValue(), staging);
            }
            validatePlaylists(staging, artifacts);
            synchronized (lock) {
                var prior = mapper.byKey(key);
                if (valid(prior) && !expired(prior, settings())) { transaction.executeWithoutResult(status -> bind(session, prior, false)); return snapshot(prior, false); }
                if (prior != null) { if (leased(text(prior, "id"))) throw fail(409, "旧缓存仍在使用中"); evict(prior); }
                var saved = new HashMap<String, Object>();
                var snapshot = ((ObjectNode) execution.deepCopy()).put("cacheId", id).put("cacheHit", false).put("state", "SUCCEEDED").put("ready", true);
                saved.put("id", id); saved.put("cache_key", key); saved.put("song_id", text(row, "song_id"));
                var actualTarget = decode(row.get("target_json"));
                if (execution.path("effectiveMode").isTextual()) actualTarget.put("mode", execution.path("effectiveMode").asText());
                saved.put("execution_json", snapshot.toString()); saved.put("target_json", actualTarget.toString());
                saved.put("artifacts_json", artifacts.toString()); saved.put("bytes", total); saved.put("created_at", now());
                boolean video = false; for (var track : execution.path("trackMapping")) if (track.path("kind").asText().equals("video")) video = true;
                saved.put("media_type", video ? "VIDEO" : "AUDIO");
                try { Files.move(staging, folder(id), StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException error) { throw fail(500, "歌曲缓存原子发布失败"); }
                try { transaction.executeWithoutResult(status -> { mapper.insert(saved); bind(session, saved, false); }); }
                catch (RuntimeException error) { try { removeTree(folder(id)); } catch (IOException ignored) {} throw error; }
                return snapshot;
            }
        } finally {
            synchronized (lock) {
                reservations.remove(staging);
                try { removeTree(staging); } catch (IOException error) { logCleanup(error); }
            }
        }
    }
    private long validateExport(JsonNode artifacts) {
        if (!artifacts.isObject() || artifacts.size() > 20000 || !artifacts.has("master.m3u8")) throw fail(502, "计算节点缓存清单无效");
        long total = 0; var fields = artifacts.fields();
        while (fields.hasNext()) {
            var entry = fields.next(); var value = entry.getValue();
            if (!ARTIFACT.matcher(entry.getKey()).matches() || !value.path("size").isIntegralNumber() || !value.path("size").canConvertToLong()
                    || value.path("size").asLong() < 0 || !value.path("sha256").asText().matches("[a-f0-9]{64}")) throw fail(502, "计算节点缓存文件清单无效");
            try { total = Math.addExact(total, value.path("size").asLong()); } catch (ArithmeticException error) { throw fail(507, "缓存产物超过容量限制"); }
        }
        if (total <= 0) throw fail(502, "计算节点缓存产物为空");
        return total;
    }
    private void download(Connection connection, String remote, String name, JsonNode metadata, Path staging) {
        var response = compute.artifact(connection, "/internal/v1/transcoding/executions/" + remote + "/artifacts/" + name, null);
        long expected = metadata.path("size").asLong();
        try (var input = response.body()) {
            if (response.statusCode() != 200 || response.headers().firstValueAsLong("Content-Length").orElse(-1) != expected) throw fail(502, "缓存文件长度与清单不符");
            if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(staging)) throw fail(500, "临时缓存目录无效");
            var digest = MessageDigest.getInstance("SHA-256"); long received = 0;
            try (var output = Files.newOutputStream(staging.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    received += count;
                    if (received > expected) throw fail(502, "缓存文件超过清单长度");
                    if (!enoughDisk(count)) throw fail(507, "缓存磁盘剩余空间不足");
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                }
            }
            if (received != expected || !HexFormat.of().formatHex(digest.digest()).equals(metadata.path("sha256").asText())) throw fail(502, "缓存文件摘要或长度校验失败");
        } catch (IOException error) { throw fail(502, "缓存文件下载或保存失败"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void validatePlaylists(Path staging, JsonNode artifacts) {
        int playlists = 0; boolean segments = false;
        var names = artifacts.fieldNames();
        try {
            while (names.hasNext()) {
                String name = names.next();
                if (name.endsWith(".ts") || name.endsWith(".m4s")) segments = true;
                if (!name.endsWith(".m3u8")) continue;
                playlists++;
                if (Files.size(staging.resolve(name)) > 4 * 1024 * 1024) throw fail(502, "缓存播放列表过大");
                String content = Files.readString(staging.resolve(name));
                if (!content.startsWith("#EXTM3U") || !name.equals("master.m3u8") && !content.contains("#EXT-X-ENDLIST")) throw fail(502, "缓存播放列表尚未完整");
                var references = new ArrayList<String>();
                for (var line : content.lines().toList()) if (!line.isBlank() && !line.startsWith("#")) references.add(line.trim());
                var matches = Pattern.compile("URI=\"([^\"]+)\"").matcher(content); while (matches.find()) references.add(matches.group(1));
                if (references.isEmpty() || references.stream().anyMatch(value -> !artifacts.has(value) || !ARTIFACT.matcher(value).matches())) throw fail(502, "缓存播放列表包含清单外引用");
            }
        } catch (IOException error) { throw fail(502, "无法验证缓存播放列表"); }
        if (playlists < 2 || !segments) throw fail(502, "缓存音视频分片不完整");
    }

    /** Open the file under the lease lock; stream it without holding the registry lock. */
    public boolean serve(String sessionId, String name, String range, boolean head, HttpServletResponse response) throws IOException {
        java.nio.channels.SeekableByteChannel channel;
        String cacheId;
        synchronized (lock) {
            var lease = mapper.lease(sessionId); if (lease == null) return false;
            var row = mapper.find(text(lease, "cache_id"));
            if (!ARTIFACT.matcher(name).matches()) throw fail(404, "缓存文件不存在");
            if (!valid(row) || !decode(row.get("artifacts_json")).has(name)) throw fail(410, "歌曲缓存已失效，请重新播放");
            if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw fail(400, "仅支持单段字节范围");
            mapper.renew(sessionId, now()); mapper.touch(text(row, "id"), now());
            channel = Files.newByteChannel(folder(text(row, "id")).resolve(name), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            cacheId = text(row, "id");
            readers.merge(cacheId, 1, Integer::sum);
        }
        try (channel) {
            long length = channel.size(), start = 0, end = length - 1;
            response.setHeader("Cache-Control", "private, no-store"); response.setHeader("X-Content-Type-Options", "nosniff"); response.setHeader("Accept-Ranges", "bytes");
            response.setContentType(name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : name.endsWith(".ts") ? "video/mp2t" : name.endsWith(".m4s") ? "video/iso.segment" : name.endsWith(".vtt") ? "text/vtt" : "video/mp4");
            if (range != null) {
                try {
                    var parts = range.substring(6).split("-", -1);
                    if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); if (suffix <= 0) throw new IllegalArgumentException(); start = Math.max(0, length - suffix); }
                    else { start = Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1])); }
                    if (start >= length || end < start) throw new IllegalArgumentException();
                } catch (IllegalArgumentException badRange) { response.setStatus(416); response.setHeader("Content-Range", "bytes */" + length); return true; }
                response.setStatus(206); response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            }
            long remaining = Math.max(0, end - start + 1); response.setContentLengthLong(remaining); channel.position(start);
            if (!head) {
                var input = Channels.newInputStream(channel); var output = response.getOutputStream(); byte[] bytes = new byte[65536];
                while (remaining > 0) { int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining)); if (count < 0) break; output.write(bytes, 0, count); remaining -= count; }
            }
            return true;
        } finally { synchronized (lock) { readers.computeIfPresent(cacheId, (id, count) -> count == 1 ? null : count - 1); } }
    }
}
