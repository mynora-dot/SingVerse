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
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
    private static final Set<String> SETTINGS_FIELDS = Set.of("maxBytes", "policy", "ttlHours", "revision", "highWatermarkPercent", "lowWatermarkPercent");
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
    private final Map<String, Capture> captures = new HashMap<>();
    private final Map<String, Integer> readers = new HashMap<>();
    private final Map<String, Integer> readerSongs = new HashMap<>();
    private final Map<String, String> readerSongIds = new HashMap<>();
    private final Map<String, CachedArtifacts> validated = new LinkedHashMap<>();
    private final Set<String> originalWriters = new HashSet<>();
    private final Map<String, OriginalIndex> originalIndexes = new HashMap<>();
    private final Map<String, Integer> downloading = new HashMap<>();
    private final Set<String> repairRequests = new HashSet<>();
    private final Map<String, String> prefetchTargets = new ConcurrentHashMap<>();
    private final Map<Path, Long> stagingBytes = new HashMap<>();
    private final Map<String, Long> entryOrphans = new HashMap<>();
    private final ThreadPoolExecutor cleanupWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> { var thread = new Thread(runnable, "song-cache-cleanup"); thread.setDaemon(true); return thread; });
    private final ThreadPoolExecutor validationWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), runnable -> { var thread = new Thread(runnable, "song-cache-validation"); thread.setDaemon(true); return thread; });
    private final Map<String, CompletableFuture<Void>> validations = new HashMap<>();
    private final Map<String, CompletableFuture<OriginalIndex>> originalLoads = new HashMap<>();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
    private final AtomicBoolean maintenanceAgain = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile boolean startupReady = true;
    private volatile long cleanupGeneration;
    private long indexedBytes = -1, orphanBytes, cleanupCooldownUntil, requestedBytes;
    private volatile CleanupJob runningJob;
    private volatile Clock clock = Clock.systemUTC();
    private static final class CleanupJob {
        final String id = UUID.randomUUID().toString(), mode, entryId;
        final boolean confirmRecent;
        final ObjectNode result;
        final CompletableFuture<ObjectNode> completion = new CompletableFuture<>();
        volatile boolean cancelled;
        CleanupJob(String mode, String entryId, boolean confirmRecent, ObjectNode result) {
            this.mode = mode; this.entryId = entryId; this.confirmRecent = confirmRecent; this.result = result;
        }
    }
    private static final class OriginalIndex {
        final NavigableMap<Long, Long> blocks = new TreeMap<>();
        final Map<String, Long> residues = new HashMap<>(), uncommittedResidues = new HashMap<>();
        long bytes, residueBytes;
        ObjectNode target;
    }
    public static final long ORIGINAL_BLOCK_BYTES = 2L * 1024 * 1024;
    private record CachedArtifacts(ObjectNode manifest, boolean valid) {}
    private record StartupCensus(long orphanBytes, Map<String, Long> entries) {}
    private static final class Capture {
        private volatile boolean cancelled;
        private InputStream body;
        void check() { if (cancelled) throw fail(410, "播放会话已结束"); }
    }


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

    @org.springframework.beans.factory.annotation.Autowired
    void configureIsolation(org.springframework.core.env.Environment environment) {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("mysql")))
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private long now() { return clock.millis(); }
    void clock(Clock value) { clock = value; }
    private static String text(Map<String, Object> row, String field) { return Objects.toString(row.get(field), ""); }
    private static long number(Map<String, Object> row, String field) { return row == null || !(row.get(field) instanceof Number value) ? 0 : value.longValue(); }
    private static Long nullableNumber(Map<String, Object> row, String field) { return row != null && row.get(field) instanceof Number value ? value.longValue() : null; }
    private static boolean activeEntry(Map<String, Object> row) { return row != null && !"DELETING".equals(row.get("state")); }
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
                .put("highWatermarkPercent", number(row, "high_watermark_percent"))
                .put("lowWatermarkPercent", number(row, "low_watermark_percent"))
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
    private long usage() { return indexedUsage() + orphanBytes + stagingBytes.values().stream().mapToLong(Long::longValue).sum(); }
    private long indexedUsage() {
        if (indexedBytes < 0) { var totals = mapper.totals(); indexedBytes = number(totals, "media_bytes") + number(totals, "entry_count") * OWNER.length(); }
        return indexedBytes;
    }
    long committedUsage() {
        return indexedUsage() + orphanBytes + reservations.values().stream().mapToLong(Long::longValue).sum();
    }
    private boolean leased(String id) { return readers.getOrDefault(id, 0) > 0 || mapper.active(id, now() - LEASE) > 0; }
    private void readerEnter(String id, String song) {
        readers.merge(id, 1, Integer::sum); readerSongIds.put(id, song); readerSongs.merge(song, 1, Integer::sum);
    }
    private void readerLeave(String id) {
        String song = readerSongIds.get(id);
        readers.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
        if (song != null) readerSongs.computeIfPresent(song, (key, count) -> count == 1 ? null : count - 1);
        if (!readers.containsKey(id)) readerSongIds.remove(id);
    }
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
    private boolean validFile(Path folder, String name, JsonNode artifacts) {
        var file = folder.resolve(name);
        try {
            return ARTIFACT.matcher(name).matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(file) == artifacts.path(name).path("size").asLong(-1);
        } catch (IOException error) { return false; }
    }
    private boolean validateFiles(Path folder, ObjectNode artifacts) {
        if (!artifacts.has("master.m3u8") || artifacts.size() > 20000 || !owned(folder)) return false;
        var names = artifacts.fieldNames();
        while (names.hasNext()) if (Thread.currentThread().isInterrupted() || !validFile(folder, names.next(), artifacts)) return false;
        return true;
    }
    /** Immutable manifests are validated once per process; hot reads inspect only their own file. */
    private ObjectNode validArtifacts(Map<String, Object> row) {
        if (!activeEntry(row) || Files.isSymbolicLink(directory)) return null;
        String id = text(row, "id");
        var folder = folder(id);
        var cached = validated.get(id);
        if (cached == null) return null;
        if (!cached.valid() || !owned(folder) || !validFile(folder, "master.m3u8", cached.manifest())) return null;
        return cached.manifest();
    }
    /** Restore a cold index on a bounded IO worker; never inspect all segments under the registry lock. */
    private void prepareArtifacts(Map<String, Object> row) {
        if (!activeEntry(row)) return;
        String id = text(row, "id"); CompletableFuture<Void> pending;
        synchronized (lock) {
            if (validated.containsKey(id) || stopping) return;
            pending = validations.get(id);
            if (pending == null) {
                pending = new CompletableFuture<>(); validations.put(id, pending);
                var completion = pending; readerEnter(id, text(row, "song_id"));
                try { validationWorker.execute(() -> {
                    try {
                        var manifest = decode(row.get("artifacts_json"));
                        boolean good = !Files.isSymbolicLink(directory) && validateFiles(folder(id), manifest);
                        synchronized (lock) { if (activeEntry(mapper.find(id))) validated.put(id, new CachedArtifacts(manifest, good)); }
                    } catch (RuntimeException failure) { logCleanup(failure); }
                    finally { synchronized (lock) { validations.remove(id); readerLeave(id); } completion.complete(null); }
                }); } catch (RejectedExecutionException busy) { validations.remove(id); readerLeave(id); completion.complete(null); }
            }
        }
        try { pending.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException busy) { /* Realtime playback can use compute while validation finishes. */ }
    }
    private boolean valid(Map<String, Object> row) { return validArtifacts(row) != null; }
    /** Stable content and output intent; task references and node origins never enter this key. */
    public String key(String songId, String fingerprint, JsonNode requestTarget, JsonNode client, boolean force, boolean explicitCodec) {
        // v2 excludes AVC produced with NVENC's incompatible single-reference/B-ref combination.
        var input = json.createObjectNode().put("version", 2).put("songId", songId).put("fingerprint", fingerprint)
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
        var candidate = mapper.byKey(key); prepareArtifacts(candidate);
        synchronized (lock) {
            var row = candidate == null ? null : mapper.find(text(candidate, "id"));
            if (!valid(row)) return null;
            transaction.executeWithoutResult(status -> bind(sessionId, row, true));
            return snapshot(row, true);
        }
    }
    public boolean contains(String cacheId) {
        if (!uuid(cacheId)) return false;
        var row = mapper.find(cacheId); prepareArtifacts(row);
        synchronized (lock) { return valid(mapper.find(cacheId)); }
    }
    public ObjectNode renew(String sessionId) {
        var lease = mapper.lease(sessionId); if (lease == null) return null;
        prepareArtifacts(mapper.find(text(lease, "cache_id")));
        synchronized (lock) {
            var current = mapper.lease(sessionId); if (current == null) return null;
            var row = mapper.find(text(current, "cache_id"));
            if (!valid(row)) return null;
            mapper.renew(sessionId, now()); mapper.touch(text(row, "id"), now());
            return snapshot(row, false);
        }
    }
    public void release(String sessionId) { synchronized (lock) { mapper.release(sessionId); } }

    public JsonNode list(int page, int pageSize) {
        if (page < 0 || page > 1_000_000 || pageSize < 1 || pageSize > 100) throw fail(400, "缓存分页参数无效");
        synchronized (lock) {
            var totals = mapper.totals(); long count = number(totals, "entry_count"), at = now();
            var entries = mapper.page((long) page * pageSize, pageSize, at - 7 * CacheEvictionPolicy.DAY, at);
            var out = json.createObjectNode().put("usageBytes", usage()).put("entryCount", count)
                    .put("page", page).put("size", pageSize).put("totalElements", count)
                    .put("committedBytes", committedUsage()).put("reservedBytes", reservations.values().stream().mapToLong(Long::longValue).sum())
                    .put("usageReady", startupReady).put("statisticsReady", mapper.statisticsReady()).put("cleanupGeneration", cleanupGeneration);
            out.set("settings", settings()); var items = out.putArray("items");
            for (var row : entries) {
                var protection = protections(row, false, at);
                var item = items.addObject().put("id", text(row, "id")).put("songId", text(row, "song_id"))
                        .put("mediaType", text(row, "media_type")).put("bytes", number(row, "bytes"))
                        .put("residueBytes", number(row, "residue_bytes"))
                        .put("createdAt", Instant.ofEpochMilli(number(row, "created_at")).toString())
                        .put("lastAccessAt", Instant.ofEpochMilli(number(row, "last_access_at")).toString())
                        .put("hits", number(row, "hits")).put("leased", protection.contains("PLAYING") || protection.contains("DOWNLOADING") || protection.contains("PREFETCH"))
                        .put("pinned", number(row, "pinned") != 0).put("pinRevision", number(row, "pin_revision"))
                        .put("state", activeEntry(row) ? "ACTIVE" : "DELETING").put("playCount7d", number(row, "plays_7d"));
                var score = score(row, at);
                item.put("H", score.H()).put("R", score.R()).put("F", score.F()).put("score", score.score())
                        .put("priority", score.priority()).put("lifecycle", score.lifecycle());
                var played = nullableNumber(row, "last_played_at");
                if (played == null) item.putNull("lastPlayedAt"); else item.put("lastPlayedAt", Instant.ofEpochMilli(played).toString());
                item.set("protectionReasons", json.valueToTree(protection));
                item.set("target", decode(row.get("target_json")));
                if (decode(row.get("target_json")).path("mode").asText().equals("ORIGINAL")) {
                    long sourceBytes = decode(row.get("target_json")).path("sourceBytes").asLong();
                    item.put("cachedBytes", number(row, "bytes")).put("sourceBytes", sourceBytes)
                            .put("complete", number(row, "bytes") == sourceBytes);
                }
                try {
                    var song = songs.get(text(row, "song_id"));
                    item.put("title", MetadataService.title(song)).put("artist", String.join(" / ", MetadataService.artists(song)));
                } catch (ResponseStatusException missing) { if (missing.getStatusCode().value() != 404) throw missing; }
            }
            return out;
        }
    }
    public JsonNode save(JsonNode body) {
        if (body == null || !body.isObject() || !body.has("maxBytes") || !body.has("policy") || !body.has("ttlHours") || !body.has("revision")) throw fail(400, "请提交完整缓存设置");
        body.fieldNames().forEachRemaining(name -> { if (!SETTINGS_FIELDS.contains(name)) throw fail(400, "未知缓存设置"); });
        long maximum = integer(body, "maxBytes"), ttl = integer(body, "ttlHours"), revision = integer(body, "revision");
        if (maximum < 64L * 1024 * 1024 || maximum > (1L << 50) || ttl < 1 || ttl > 8760 || revision < 0
                || !body.path("policy").isTextual() || !Set.of("HYBRID", "LRU", "LFU", "TTL", "MANUAL").contains(body.path("policy").asText()))
            throw fail(400, "缓存容量、保留时间或清理策略无效");
        synchronized (lock) {
            var current = settings();
            long high = body.has("highWatermarkPercent") ? integer(body, "highWatermarkPercent") : current.path("highWatermarkPercent").asLong();
            long low = body.has("lowWatermarkPercent") ? integer(body, "lowWatermarkPercent") : current.path("lowWatermarkPercent").asLong();
            if (low < 1 || low >= high || high > 100) throw fail(400, "水位必须为 1～100 的整数，低水位小于高水位");
            String policy = body.path("policy").asText().equals("MANUAL") ? "MANUAL" : "HYBRID";
            if (mapper.saveSettings(Map.of("maxBytes", maximum, "policy", policy, "ttlHours", ttl, "revision", revision,
                    "highWatermarkPercent", high, "lowWatermarkPercent", low)) != 1)
                throw fail(409, "缓存设置已变化，请重新读取");
            cleanupCooldownUntil = 0; requestMaintenance();
            return settings();
        }
    }
    private static long integer(JsonNode body, String key) {
        var value = body.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw fail(400, "缓存设置必须使用整数");
        return value.longValue();
    }
    public void delete(String id) { delete(id, false); }
    public void delete(String id, boolean confirmRecent) {
        synchronized (lock) {
            var row = uuid(id) ? mapper.find(id) : null;
            if (row == null) throw fail(404, "缓存不存在");
            var current = withProfile(row);
            var reasons = protections(current, true, now());
            if (confirmRecent) reasons.remove("RECENT_PLAY");
            if (!reasons.isEmpty()) throw fail(409, "缓存受保护，请停止使用或取消固定后重试：" + String.join(",", reasons));
        }
        if (awaitJob(submit("DELETE", id, confirmRecent)).path("removed").asInt() == 0)
            throw fail(409, "缓存状态已变化或仍受保护，请查看清理记录");
    }
    public JsonNode clear() { return awaitJob(submit("CLEAR", "", false)); }
    private ObjectNode awaitJob(CleanupJob job) {
        try { return job.completion.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw fail(503, "缓存清理等待已中断，任务将在后台继续"); }
        catch (ExecutionException failure) { throw fail(500, "缓存清理未完成，请查看清理记录"); }
    }
    public JsonNode cleanupJob(JsonNode request) {
        if (request == null || !request.isObject()) throw fail(400, "清理参数无效");
        request.fieldNames().forEachRemaining(name -> { if (!Set.of("mode", "entryId", "confirmRecent").contains(name)) throw fail(400, "未知清理参数"); });
        String mode = request.path("mode").asText();
        if (!Set.of("POLICY", "CLEAR", "DELETE").contains(mode)
                || request.has("confirmRecent") && !request.path("confirmRecent").isBoolean()) throw fail(400, "清理模式无效");
        String entry = request.path("entryId").asText();
        if (mode.equals("DELETE") && !uuid(entry) || !mode.equals("DELETE") && (!entry.isEmpty() || request.path("confirmRecent").asBoolean())) throw fail(400, "清理目标无效");
        return job(submit(mode, entry, request.path("confirmRecent").asBoolean()).id);
    }
    public JsonNode job(String id) {
        var row = uuid(id) ? mapper.job(id) : null;
        if (row == null) throw fail(404, "清理任务不存在");
        return decode(row.get("result_json"));
    }
    public JsonNode cancelJob(String id) {
        synchronized (lock) {
            var job = runningJob;
            if (job != null && job.id.equals(id)) { job.cancelled = true; return ((ObjectNode) job(id)).put("state", "CANCELLING"); }
        }
        return job(id);
    }
    private CleanupJob createJob(String mode, String entry, boolean confirmRecent) {
        var out = json.createObjectNode().put("mode", mode).put("state", "QUEUED").put("removed", 0).put("skipped", 0)
                .put("failed", 0).put("freedBytes", 0).put("usageBeforeBytes", 0).put("usageAfterBytes", 0)
                .put("targetBytes", 0).put("targetReached", false).put("spaceInsufficient", false).put("shortfallBytes", 0);
        out.putObject("skippedByReason"); out.putArray("errors");
        var job = new CleanupJob(mode, entry, confirmRecent, out); out.put("id", job.id);
        mapper.insertJob(Map.of("id", job.id, "mode", mode, "state", "QUEUED", "now", now(), "result", out.toString()));
        return job;
    }
    private CleanupJob submit(String mode, String entry, boolean confirmRecent) {
        synchronized (lock) {
            if (stopping) throw fail(503, "缓存服务正在停止");
            if (mode.equals("DELETE") && mapper.find(entry) == null) throw fail(404, "缓存不存在");
            if (runningJob != null) {
                if (runningJob.mode.equals(mode) && runningJob.entryId.equals(entry) && runningJob.confirmRecent == confirmRecent) return runningJob;
                throw fail(409, "已有缓存清理任务正在执行");
            }
            var job = createJob(mode, entry, confirmRecent); runningJob = job;
            try { cleanupWorker.execute(() -> runJob(job)); }
            catch (RejectedExecutionException failure) {
                runningJob = null; job.result.put("state", "FAILED"); saveJob(job); throw fail(503, "缓存维护繁忙，请稍后重试");
            }
            return job;
        }
    }
    private void saveJob(CleanupJob job) { mapper.updateJob(job.id, job.result.path("state").asText(), job.result.toString(), now()); }
    private Map<String, Object> withProfile(Map<String, Object> row) {
        var out = new HashMap<>(row); var profile = mapper.profile(text(row, "song_id"));
        if (profile != null) out.putAll(profile);
        return out;
    }
    private static CacheEvictionPolicy.Score score(Map<String, Object> row, long at) {
        double heat = row.get("heat") instanceof Number value ? value.doubleValue() : 0;
        return CacheEvictionPolicy.score(heat, nullableNumber(row, "heat_updated_at"), nullableNumber(row, "last_played_at"),
                number(row, "plays_7d"), row.containsKey("first_cached_at") ? number(row, "first_cached_at") : number(row, "created_at"), at);
    }
    private List<String> protections(Map<String, Object> row, boolean refresh, long at) {
        var reasons = new ArrayList<String>(); String song = text(row, "song_id");
        if (!activeEntry(row)) reasons.add("DELETING");
        boolean playing = readerSongs.getOrDefault(song, 0) > 0
                || (refresh ? mapper.playing(song, at) > 0 || mapper.activeSong(song, at - LEASE) > 0
                            : number(row, "playing_count") > 0 || number(row, "lease_count") > 0);
        if (playing) reasons.add("PLAYING");
        if (downloading.getOrDefault(song, 0) > 0) reasons.add("DOWNLOADING");
        if (number(row, "pinned") != 0) reasons.add("PINNED");
        if (CacheEvictionPolicy.recent(nullableNumber(row, "last_played_at"), at)) reasons.add("RECENT_PLAY");
        if (prefetchTargets.containsValue(song)) reasons.add("PREFETCH");
        return reasons;
    }
    public JsonNode pins(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw fail(400, "固定缓存分页参数无效");
        var result = json.createObjectNode().put("page", page).put("size", size).put("totalElements", mapper.pinCount());
        var items = result.putArray("items");
        for (var row : mapper.pins((long) page * size, size)) {
            String songId = text(row, "song_id");
            var item = items.addObject().put("songId", songId).put("pinned", true).put("revision", number(row, "pin_revision")).put("hasCache", number(row, "cache_count") > 0);
            try { var song = songs.get(songId); item.put("title", MetadataService.title(song)).put("artist", String.join(" / ", MetadataService.artists(song))); }
            catch (ResponseStatusException missing) { if (missing.getStatusCode().value() != 404) throw missing; }
        }
        return result;
    }
    private void lockSong(String song) {
        if (mapper.lockProfile(song) == 0) {
            try { mapper.ensureProfile(song); } catch (org.springframework.dao.DuplicateKeyException raced) { /* Another first writer created the shared lock row. */ }
            mapper.lockProfile(song);
        }
    }
    public JsonNode pin(String song, JsonNode request) {
        if (song == null || song.isBlank() || song.length() > 64 || song.chars().anyMatch(Character::isISOControl)
                || request == null || !request.isObject() || request.size() != 2 || !request.path("pinned").isBoolean()
                || !request.has("revision")) throw fail(400, "固定缓存参数无效");
        long revision = integer(request, "revision"); if (revision < 0) throw fail(400, "固定缓存版本无效");
        var result = transaction.execute(status -> {
            lockSong(song);
            if (request.path("pinned").asBoolean() && mapper.deletingForSong(song) > 0) throw fail(409, "该歌曲已有缓存正在删除，请待清理完成后重试固定");
            if (mapper.pin(song, request.path("pinned").asBoolean() ? 1 : 0, revision) != 1) throw fail(409, "固定状态已变化，请重新读取");
            return json.createObjectNode().put("songId", song).put("pinned", request.path("pinned").asBoolean()).put("revision", revision + 1);
        });
        synchronized (lock) { cleanupCooldownUntil = 0; }
        requestMaintenance(); return result;
    }
    public void protectPrefetch(String owner, String song) {
        if (song == null || song.isBlank()) prefetchTargets.remove(owner); else prefetchTargets.put(owner, song);
    }
    public long cleanupGeneration() { return cleanupGeneration; }
    private void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) throw new IOException("Refusing a symbolic cache directory");
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root) && !path.equals(root.resolve(OWNER_FILE))) Files.deleteIfExists(path);
            }
        }
        Files.deleteIfExists(root.resolve(OWNER_FILE)); Files.deleteIfExists(root);
    }
    /** Mark under the registry lock; recursive filesystem work always belongs to the cleanup worker. */
    private void evict(Map<String, Object> row) {
        if (!activeEntry(row)) return;
        repairRequests.add(text(row, "id"));
        if (mapper.statisticsReady() && claim(row, false)) repairRequests.remove(text(row, "id"));
        requestMaintenance();
    }

    private boolean claim(Map<String, Object> candidate, boolean confirmRecent) {
        synchronized (lock) {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                String song = text(candidate, "song_id"); lockSong(song);
                var current = mapper.find(text(candidate, "id")); if (!activeEntry(current)) return false;
                var reasons = protections(withProfile(current), true, now());
                if (confirmRecent) reasons.remove("RECENT_PLAY");
                if (!reasons.isEmpty()) return false;
                if (mapper.claimDelete(text(current, "id"), UUID.randomUUID().toString()) != 1) return false;
                validated.remove(text(current, "id")); return true;
            }));
        }
    }
    private long finishDelete(Map<String, Object> row) throws IOException {
        String id = text(row, "id"), token = text(row, "delete_token");
        if (!uuid(token)) throw new IOException("Invalid deletion token");
        Path original = folder(id), quarantine = directory.resolve(".deleting-" + id + "-" + token);
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            if (!owned(original) || Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache ownership changed");
            Files.move(original, quarantine, StandardCopyOption.ATOMIC_MOVE);
        }
        long freed = 0;
        if (Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) {
            if (!owned(quarantine)) {
                if (Files.isSymbolicLink(quarantine) || !Files.isDirectory(quarantine, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Cache ownership changed");
                try (var children = Files.list(quarantine)) { if (children.findAny().isPresent()) throw new IOException("Cache ownership changed"); }
            }
            freed = size(quarantine); removeTree(quarantine);
        }
        synchronized (lock) {
            indexedUsage();
            transaction.executeWithoutResult(status -> { mapper.releaseCache(id); mapper.delete(id); });
            indexedBytes = Math.max(0, indexedUsage() - number(row, "bytes") - number(row, "residue_bytes") - OWNER.length());
            validated.remove(id);
            Long entryOrphan = entryOrphans.remove(id);
            if (entryOrphan != null) orphanBytes = Math.max(0, orphanBytes - entryOrphan);
            var originalIndex = originalIndexes.remove(id);
            if (originalIndex != null) orphanBytes = Math.max(0, orphanBytes - originalIndex.uncommittedResidues.values().stream().mapToLong(Long::longValue).sum());
            cleanupGeneration++;
        }
        return freed;
    }
    private void releaseStaging(Path staging) {
        long residue = 0;
        try { removeTree(staging); }
        catch (IOException failure) { logCleanup(failure); try { residue = size(staging); } catch (RuntimeException ignored) { residue = stagingBytes.getOrDefault(staging, 0L); } }
        synchronized (lock) { orphanBytes += residue; reservations.remove(staging); stagingBytes.remove(staging); }
    }
    private void cleanupPartial() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return;
        var known = new HashSet<String>(); for (var row : mapper.paths()) known.add(text(row, "id"));
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) {
                String name = path.getFileName().toString(); boolean orphan;
                synchronized (lock) {
                    orphan = !reservations.containsKey(path) && !known.contains(name)
                            && (name.matches("\\.partial-[a-f0-9-]{36}") || uuid(name) && mapper.find(name) == null);
                }
                if (orphan && owned(path)) { long released = size(path); removeTree(path); synchronized (lock) { orphanBytes = Math.max(0, orphanBytes - released); } }
            }
        } catch (IOException error) { throw fail(500, "临时缓存清理失败，请检查目录权限"); }
    }
    /** No writer or original-index restore starts until this background, per-entry census finishes. */
    private StartupCensus startupCensus() {
        var expected = new HashMap<String, Long>(); var owners = new HashMap<String, String>();
        for (var row : mapper.paths()) {
            String id = text(row, "id"); expected.put(id, number(row, "bytes") + number(row, "residue_bytes") + OWNER.length());
            owners.put(id, id);
            String token = text(row, "delete_token");
            if (uuid(token)) owners.put(".deleting-" + id + "-" + token, id);
        }
        var actual = new HashMap<String, Long>(); long orphan = 0;
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) {
                long bytes = size(file); String id = owners.get(file.getFileName().toString());
                if (id == null) orphan = Math.addExact(orphan, bytes); else actual.merge(id, bytes, Math::addExact);
            }
        } catch (IOException | ArithmeticException unavailable) { throw fail(500, "无法恢复歌曲缓存用量"); }
        var attributed = new HashMap<String, Long>();
        for (var entry : actual.entrySet()) {
            long extra = Math.max(0, entry.getValue() - expected.get(entry.getKey()));
            if (extra > 0) { attributed.put(entry.getKey(), extra); orphan = Math.addExact(orphan, extra); }
        }
        return new StartupCensus(orphan, attributed);
    }
    private long trim(long needed) {
        long committed = committedUsage(); var config = settings();
        if (!config.path("policy").asText().equals("MANUAL") && (committed + needed >= config.path("maxBytes").asLong() * config.path("highWatermarkPercent").asLong() / 100
                || !enoughDisk(needed))) requestMaintenance();
        return startupReady ? committed : Math.max(committed, config.path("maxBytes").asLong());
    }
    private void capacityPressure(long needed) {
        if (!settings().path("policy").asText().equals("MANUAL")) { requestedBytes = Math.max(requestedBytes, needed); requestMaintenance(); }
    }
    private boolean enoughDisk(long needed) {
        try {
            long pending;
            synchronized (lock) { pending = Math.max(0, reservations.values().stream().mapToLong(Long::longValue).sum() - stagingBytes.values().stream().mapToLong(Long::longValue).sum()); }
            return Files.getFileStore(directory).getUsableSpace() - needed - pending >= minimumFreeBytes;
        } catch (IOException error) { throw fail(507, "无法读取歌曲缓存可用磁盘空间"); }
    }
    private long diskShortfall(long needed) {
        try {
            long pending = Math.max(0, reservations.values().stream().mapToLong(Long::longValue).sum() - stagingBytes.values().stream().mapToLong(Long::longValue).sum());
            return Math.max(0, minimumFreeBytes + needed + pending - Files.getFileStore(directory).getUsableSpace());
        } catch (IOException unavailable) { return Math.max(1, needed); }
    }
    /** Cache capacity is advisory for realtime playback; never synchronously evict or reject decoding. */
    public void assertCapacity() { synchronized (lock) { ensureDirectory(); trim(1); } }
    @jakarta.annotation.PostConstruct
    public void startMaintenance() { startupReady = false; requestMaintenance(); }
    @Scheduled(fixedDelay = 10000, initialDelay = 10000, scheduler = "transcodeScheduler")
    public void maintain() { requestMaintenance(); }
    private void requestMaintenance() {
        if (stopping) return;
        if (!maintenanceQueued.compareAndSet(false, true)) { maintenanceAgain.set(true); return; }
        try { cleanupWorker.execute(() -> {
            try {
                ensureDirectory();
                if (!startupReady) {
                    for (var row : mapper.interruptedJobs()) {
                        if (runningJob != null && runningJob.id.equals(text(row, "id"))) continue;
                        var result = decode(row.get("result_json")).put("state", "INTERRUPTED");
                        mapper.updateJob(text(row, "id"), "INTERRUPTED", result.toString(), now());
                    }
                }
                mapper.expire(now() - LEASE); cleanupPartial();
                synchronized (lock) {
                    for (String id : new ArrayList<>(repairRequests)) {
                        var row = mapper.find(id);
                        if (!activeEntry(row) || mapper.statisticsReady() && claim(row, false)) repairRequests.remove(id);
                    }
                }
                for (var row : mapper.pendingDeletes(now())) try { finishDelete(row); }
                    catch (IOException | RuntimeException failure) { mapper.failedDelete(text(row, "id"), now() + 60_000, "缓存文件删除失败，将自动重试"); logCleanup(failure); }
                if (!startupReady) {
                    var census = startupCensus();
                    synchronized (lock) {
                        orphanBytes = census.orphanBytes(); entryOrphans.clear(); entryOrphans.putAll(census.entries());
                        indexedUsage(); startupReady = true;
                    }
                }
                inspectBackground();
                CleanupJob automatic = null;
                synchronized (lock) {
                    var config = settings(); long committed = committedUsage(), maximum = config.path("maxBytes").asLong();
                    if (runningJob == null && now() >= cleanupCooldownUntil && mapper.statisticsReady()
                            && !config.path("policy").asText().equals("MANUAL")
                            && (committed + requestedBytes >= maximum * config.path("highWatermarkPercent").asLong() / 100 || !enoughDisk(requestedBytes))) {
                        automatic = createJob("POLICY", "", false); runningJob = automatic;
                    }
                }
                if (automatic != null) runJob(automatic);
                mapper.pruneJobs(now() - 7 * CacheEvictionPolicy.DAY);
            } catch (RuntimeException failure) { logCleanup(failure); }
            finally { maintenanceQueued.set(false); if (maintenanceAgain.getAndSet(false)) requestMaintenance(); }
        }); }
        catch (RejectedExecutionException full) { maintenanceQueued.set(false); maintenanceAgain.set(true); }
    }
    private void inspectBackground() {
        String id; ObjectNode manifest;
        synchronized (lock) {
            var iterator = validated.entrySet().iterator(); if (!iterator.hasNext()) return;
            var entry = iterator.next(); id = entry.getKey(); var cached = entry.getValue(); manifest = cached.manifest();
            iterator.remove(); validated.put(id, cached);
        }
        boolean valid = !Files.isSymbolicLink(directory) && validateFiles(folder(id), manifest);
        synchronized (lock) {
            if (activeEntry(mapper.find(id))) validated.computeIfPresent(id, (key, current) -> current.manifest() == manifest ? new CachedArtifacts(manifest, valid) : current);
        }
    }
    private void runJob(CleanupJob job) {
        long needed;
        synchronized (lock) { needed = requestedBytes; requestedBytes = 0; job.result.put("usageBeforeBytes", committedUsage()).put("state", "RUNNING"); }
        try {
            saveJob(job); if (!job.mode.equals("DELETE")) cleanupPartial(); var config = settings(); long at = now();
            long target = config.path("maxBytes").asLong() * config.path("lowWatermarkPercent").asLong() / 100;
            job.result.put("targetBytes", target);
            if (!mapper.statisticsReady() && !(job.mode.equals("DELETE") && job.confirmRecent)
                    || job.mode.equals("POLICY") && config.path("policy").asText().equals("MANUAL")) {
                job.result.put("state", "BLOCKED"); skipped(job, !mapper.statisticsReady() ? "STATISTICS_NOT_READY" : "MANUAL");
            } else {
                var candidates = job.mode.equals("DELETE") ? new ArrayList<Map<String, Object>>() : new ArrayList<>(mapper.candidates(at - 7 * CacheEvictionPolicy.DAY, at));
                if (job.mode.equals("DELETE")) { var row = mapper.find(job.entryId); if (row != null) candidates.add(withProfile(row)); }
                candidates = rankCandidates(candidates, at);
                for (var candidate : candidates) {
                    if (job.cancelled || stopping) break;
                    synchronized (lock) {
                        if (job.mode.equals("POLICY") && committedUsage() <= target && committedUsage() + needed <= config.path("maxBytes").asLong() && enoughDisk(needed)) break;
                        var reasons = protections(candidate, false, at);
                        if (job.confirmRecent) reasons.remove("RECENT_PLAY");
                        if (!reasons.isEmpty()) { skipped(job, reasons.get(0)); continue; }
                    }
                    try {
                        if (!claim(candidate, job.confirmRecent)) { skipped(job, "CHANGED"); continue; }
                        var marked = mapper.find(text(candidate, "id"));
                        long freed = finishDelete(marked);
                        job.result.put("removed", job.result.path("removed").asInt() + 1)
                                .put("freedBytes", job.result.path("freedBytes").asLong() + freed);
                    } catch (IOException | RuntimeException failure) {
                        String id = text(candidate, "id"); mapper.failedDelete(id, now() + 60_000, "缓存文件删除失败，将自动重试");
                        job.result.put("failed", job.result.path("failed").asInt() + 1);
                        if (job.result.withArray("errors").size() < 20) job.result.withArray("errors").addObject().put("id", id).put("message", "删除失败，索引已保留以供重试");
                        logCleanup(failure);
                    }
                    saveJob(job);
                }
                job.result.put("state", job.cancelled || stopping ? "CANCELLED" : "SUCCEEDED");
            }
            if (job.cancelled || stopping) job.result.put("state", "CANCELLED");
            synchronized (lock) {
                long after = committedUsage(), maximum = config.path("maxBytes").asLong();
                boolean reached = job.mode.equals("POLICY") ? after <= target : job.result.path("skipped").asInt() == 0 && job.result.path("failed").asInt() == 0;
                boolean insufficient = after + needed > maximum || !enoughDisk(needed);
                job.result.put("usageAfterBytes", after).put("targetReached", reached).put("spaceInsufficient", insufficient)
                        .put("shortfallBytes", Math.max(Math.max(0, after + needed - maximum), diskShortfall(needed)));
                if (job.result.path("state").asText().equals("SUCCEEDED") && (!reached || job.result.path("failed").asInt() > 0))
                    job.result.put("state", job.result.path("removed").asInt() == 0 && job.result.path("failed").asInt() == 0 ? "BLOCKED" : "PARTIAL");
            }
        } catch (RuntimeException failure) { job.result.put("state", "FAILED"); logCleanup(failure); }
        finally {
            try { saveJob(job); } catch (RuntimeException failure) { logCleanup(failure); }
            synchronized (lock) { if (runningJob == job) runningJob = null; cleanupCooldownUntil = now() + 60_000; cleanupGeneration++; }
            job.completion.complete(job.result.deepCopy());
            if (maintenanceAgain.getAndSet(false)) requestMaintenance();
            LoggerFactory.getLogger(SongCacheService.class).info("Song cache cleanup {}: state={}, removed={}, skipped={}, failed={}, targetReached={}, spaceInsufficient={}",
                    job.id, job.result.path("state").asText(), job.result.path("removed").asInt(), job.result.path("skipped").asInt(), job.result.path("failed").asInt(),
                    job.result.path("targetReached").asBoolean(), job.result.path("spaceInsufficient").asBoolean());
        }
    }
    private record Ranked(Map<String, Object> row, CacheEvictionPolicy.Score score) {}
    static ArrayList<Map<String, Object>> rankCandidates(List<Map<String, Object>> candidates, long at) {
        var ranked = new ArrayList<Ranked>(candidates.size());
        for (var row : candidates) ranked.add(new Ranked(row, score(row, at)));
        ranked.sort(Comparator.comparingDouble((Ranked value) -> value.score().priority())
                .thenComparingInt(value -> switch (value.score().lifecycle()) { case "EVICT" -> 0; case "COLD" -> 1; case "WARM" -> 2; default -> 3; })
                .thenComparing(Comparator.<Ranked>comparingLong(value -> number(value.row(), "bytes") + number(value.row(), "residue_bytes")).reversed())
                .thenComparingLong(value -> nullableNumber(value.row(), "last_played_at") == null ? Long.MIN_VALUE : number(value.row(), "last_played_at"))
                .thenComparing(value -> text(value.row(), "id")));
        var result = new ArrayList<Map<String, Object>>(ranked.size()); for (var value : ranked) result.add(value.row());
        return result;
    }
    private void skipped(CleanupJob job, String reason) {
        job.result.put("skipped", job.result.path("skipped").asInt() + 1);
        var reasons = job.result.with("skippedByReason"); reasons.put(reason, reasons.path(reason).asInt() + 1);
    }
    void awaitMaintenance() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!maintenanceQueued.get() && runningJob == null && cleanupWorker.getActiveCount() == 0 && cleanupWorker.getQueue().isEmpty()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("Cache maintenance did not finish");
    }
    @jakarta.annotation.PreDestroy
    public void close() {
        stopping = true; var job = runningJob; if (job != null) job.cancelled = true;
        cleanupWorker.shutdownNow(); validationWorker.shutdownNow();
        if (job != null) job.completion.complete(json.createObjectNode().put("id", job.id).put("state", "CANCELLED"));
        try { cleanupWorker.awaitTermination(2, TimeUnit.SECONDS); validationWorker.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private void logCleanup(Exception failure) { LoggerFactory.getLogger(SongCacheService.class).warn("Song cache cleanup deferred; retained deletion journal for retry ({})", failure.getClass().getSimpleName()); }

    /** Original files share the existing owned directory, budget, eviction and reader protection. */
    public Original original(String songId, String fingerprint, long sourceBytes, boolean create) {
        String key = com.mynora.singverse.auth.crypto.AuthCrypto.hash("original:v1:" + songId + ":" + fingerprint);
        Map<String, Object> row; String id; CompletableFuture<OriginalIndex> loading = null;
        synchronized (lock) {
            // The startup census owns filesystem accounting; playback continues at its source meanwhile.
            if (!startupReady || stopping) return null;
            ensureDirectory();
            row = mapper.byKey(key);
            if (row != null && !activeEntry(row)) return null;
            if (sourceBytes <= 0 && row != null) sourceBytes = decode(row.get("target_json")).path("sourceBytes").asLong();
            if (sourceBytes <= 0 || sourceBytes > (1L << 50)) return null;
            if (row != null && (!owned(folder(text(row, "id")))
                    || decode(row.get("target_json")).path("sourceBytes").asLong() != sourceBytes)) {
                if (leased(text(row, "id"))) return null;
                evict(row); return null;
            }
            if (row == null) {
                if (!create) return null;
                long minimum = Math.min(sourceBytes, ORIGINAL_BLOCK_BYTES) + 2L * OWNER.length();
                long committed = trim(minimum);
                if (committed + minimum > settings().path("maxBytes").asLong() || !enoughDisk(minimum)) { capacityPressure(minimum); return null; }
                id = UUID.randomUUID().toString();
                var target = json.createObjectNode().put("mode", "ORIGINAL").put("sourceBytes", sourceBytes)
                        .put("blockBytes", ORIGINAL_BLOCK_BYTES);
                var saved = new HashMap<String, Object>();
                saved.put("id", id); saved.put("cache_key", key); saved.put("song_id", songId);
                saved.put("target_json", target.toString()); saved.put("execution_json", "{}");
                saved.put("artifacts_json", "{}");
                String relative = songs.get(songId).relativePath();
                saved.put("media_type", com.mynora.singverse.media.filename.MediaFileTypes.contentType(relative).startsWith("audio/") ? "AUDIO" : "VIDEO");
                saved.put("bytes", 0L); saved.put("created_at", now());
                try {
                    Files.createDirectory(folder(id));
                    Files.writeString(folder(id).resolve(OWNER_FILE), OWNER, StandardOpenOption.CREATE_NEW);
                    transaction.executeWithoutResult(status -> { lockSong(songId); mapper.insert(saved); }); indexedBytes = indexedUsage() + OWNER.length();
                } catch (IOException | RuntimeException failure) {
                    orphanBytes += OWNER.length(); requestMaintenance();
                    throw fail(507, "原片缓存目录不可写或空间不足");
                }
                row = saved;
            }
            id = text(row, "id");
            var index = originalIndexes.get(id);
            if (index != null) { mapper.hit(id, now()); readerEnter(id, songId); return new Original(id, songId, sourceBytes, index); }
            readerEnter(id, songId);
            loading = originalLoads.get(id);
            if (loading == null) {
                loading = new CompletableFuture<>(); originalLoads.put(id, loading);
                var completion = loading; var saved = row; long total = sourceBytes; String cacheId = id;
                try { validationWorker.execute(() -> {
                    try { completion.complete(restoreOriginal(saved, total)); }
                    catch (RuntimeException failure) { completion.completeExceptionally(failure); }
                    finally { synchronized (lock) { originalLoads.remove(cacheId); } }
                }); } catch (RejectedExecutionException busy) { originalLoads.remove(id); completion.complete(null); }
            }
        }
        boolean handedOff = false;
        try {
            var index = loading.get(2, TimeUnit.SECONDS);
            synchronized (lock) {
                if (index != null && activeEntry(mapper.find(id))) { mapper.hit(id, now()); handedOff = true; return new Original(id, songId, sourceBytes, index); }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException busy) { /* Continue at the source while the bounded restore finishes. */ }
        finally { if (!handedOff) synchronized (lock) { readerLeave(id); } }
        return null;
    }
    private OriginalIndex restoreOriginal(Map<String, Object> row, long total) {
        String id = text(row, "id"); var index = new OriginalIndex(); index.target = decode(row.get("target_json"));
        if (!owned(folder(id))) return null;
        try (var files = Files.list(folder(id))) {
            for (var file : files.toList()) {
                if (Thread.currentThread().isInterrupted()) return null;
                String name = file.getFileName().toString();
                if (name.equals(OWNER_FILE) || Files.isSymbolicLink(file)) continue;
                long actual = size(file), offset = -1;
                if (name.matches("original_[0-9]+\\.block") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try { offset = Math.multiplyExact(Long.parseLong(name.substring(9, name.length() - 6)), ORIGINAL_BLOCK_BYTES); }
                    catch (ArithmeticException | NumberFormatException invalid) { /* Retain its occupied bytes as unusable residue. */ }
                }
                long expected = offset < 0 ? 0 : Math.min(ORIGINAL_BLOCK_BYTES, total - offset);
                if (expected > 0 && name.equals("original_" + offset / ORIGINAL_BLOCK_BYTES + ".block") && actual == expected) {
                    index.blocks.put(offset, expected); index.bytes = Math.addExact(index.bytes, expected);
                }
                else { index.residues.put(name, actual); index.residueBytes = Math.addExact(index.residueBytes, actual); }
            }
        } catch (IOException missing) { return null; }
        synchronized (lock) {
            var current = mapper.find(id); if (!activeEntry(current)) return null;
            if (index.bytes != number(current, "bytes") || index.residueBytes != number(current, "residue_bytes")) {
                repairOriginalBytes(id, index.bytes, index.residueBytes, number(current, "bytes"), number(current, "residue_bytes"));
            }
            Long entryOrphan = entryOrphans.remove(id);
            if (entryOrphan != null) orphanBytes = Math.max(0, orphanBytes - entryOrphan);
            originalIndexes.put(id, index); return index;
        }
    }
    /** Persistent valid and unusable bytes move together; a failed metadata write changes neither ledger. */
    private void repairOriginalBytes(String id, long bytes, long residue, long previousBytes, long previousResidue) {
        indexedUsage();
        if (mapper.repairOriginalBytes(id, bytes, residue, now()) != 1) throw fail(507, "原片缓存索引暂不可写");
        indexedBytes = Math.max(0, indexedBytes + bytes + residue - previousBytes - previousResidue);
    }

    public final class Original implements AutoCloseable {
        private final String id;
        private final String songId;
        private final long total;
        private final OriginalIndex index;
        private boolean closed;
        private Original(String id, String songId, long total, OriginalIndex index) { this.id = id; this.songId = songId; this.total = total; this.index = index; }
        public long sourceBytes() { return total; }
        public long blockEnd(long offset) { return Math.min(total, (offset / ORIGINAL_BLOCK_BYTES + 1) * ORIGINAL_BLOCK_BYTES) - 1; }
        private String name(long offset) { return "original_" + (offset / ORIGINAL_BLOCK_BYTES) + ".block"; }
        public boolean present(long offset) {
            synchronized (lock) {
                long offsetStart = offset / ORIGINAL_BLOCK_BYTES * ORIGINAL_BLOCK_BYTES;
                if (closed || !index.blocks.containsKey(offsetStart)) return false;
                Path file = folder(id).resolve(name(offset));
                long expected = Math.min(ORIGINAL_BLOCK_BYTES, total - offset / ORIGINAL_BLOCK_BYTES * ORIGINAL_BLOCK_BYTES);
                if (!owned(folder(id))) return false;
                long residue = expected;
                try {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        residue = Files.size(file); if (residue == expected) return true;
                    } else if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) residue = 0;
                } catch (IOException failure) { /* Keep the old occupied-byte upper bound until a background restore can inspect it. */ }
                long nextResidue = index.residueBytes - index.residues.getOrDefault(name(offset), 0L) + residue;
                repairOriginalBytes(id, index.bytes - expected, nextResidue, index.bytes, index.residueBytes);
                index.blocks.remove(offsetStart); index.bytes -= expected; index.residues.put(name(offset), residue); index.residueBytes = nextResidue;
                return false;
            }
        }
        public long missingEnd(long start, long end) {
            synchronized (lock) { var next = index.blocks.ceilingKey((start / ORIGINAL_BLOCK_BYTES + 1) * ORIGINAL_BLOCK_BYTES); return next == null ? end : Math.min(end, next - 1); }
        }
        public long cachedBytes() { synchronized (lock) { return index.bytes; } }
        public boolean partialUsable() { synchronized (lock) { return index.bytes == 0 || index.bytes == total || index.target.path("rangeSupported").asBoolean(); } }
        public void rangeSupported() {
            synchronized (lock) {
                if (!index.target.path("rangeSupported").asBoolean()) { index.target.put("rangeSupported", true); mapper.target(id, index.target.toString()); }
            }
        }
        public byte[] read(long start, long end, Runnable guard) throws IOException {
            try (var channel = Files.newByteChannel(folder(id).resolve(name(start)), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (!present(start) || channel.size() != blockEnd(start) - start / ORIGINAL_BLOCK_BYTES * ORIGINAL_BLOCK_BYTES + 1)
                    throw new IOException("Original cache block changed");
                channel.position(start % ORIGINAL_BLOCK_BYTES);
                var input = Channels.newInputStream(channel); byte[] buffer = new byte[(int) (end - start + 1)];
                int offset = 0; long remaining = buffer.length, nextCheck = 0;
                while (remaining > 0) {
                    if (System.nanoTime() >= nextCheck) { guard.run(); nextCheck = System.nanoTime() + 250_000_000L; }
                    int count = input.read(buffer, offset, (int) Math.min(65536, remaining));
                    if (count < 0) throw new EOFException("Original cache block truncated");
                    offset += count; remaining -= count;
                }
                guard.run();
                return buffer;
            }
        }
        /** Reserve once for a contiguous source response; block publication never rescans the cache. */
        public OriginalWriter writer(long start, long end, Runnable guard) {
            synchronized (lock) {
                long first = (start + ORIGINAL_BLOCK_BYTES - 1) / ORIGINAL_BLOCK_BYTES * ORIGINAL_BLOCK_BYTES;
                long last = end == total - 1 ? total : (end + 1) / ORIGINAL_BLOCK_BYTES * ORIGINAL_BLOCK_BYTES;
                long needed = Math.min(32L * 1024 * 1024, Math.max(0, last - first));
                if (needed == 0 || closed) return new OriginalWriter(this, start, null, 0, guard);
                var config = settings();
                long committed = trim(needed + OWNER.length());
                long budget = Math.min(needed, config.path("maxBytes").asLong() - committed - OWNER.length());
                try { budget = Math.min(budget, Files.getFileStore(directory).getUsableSpace() - minimumFreeBytes - OWNER.length()); }
                catch (IOException failure) { budget = 0; }
                if (budget < Math.min(ORIGINAL_BLOCK_BYTES, total - first)) return new OriginalWriter(this, start, null, 0, guard);
                Path staging = directory.resolve(".partial-" + UUID.randomUUID());
                try {
                    Files.createDirectory(staging); Files.writeString(staging.resolve(OWNER_FILE), OWNER, StandardOpenOption.CREATE_NEW);
                } catch (IOException failure) { throw fail(507, "原片缓存预留失败"); }
                reservations.put(staging, budget + OWNER.length());
                stagingBytes.put(staging, (long) OWNER.length()); downloading.merge(songId, 1, Integer::sum);
                return new OriginalWriter(this, start, staging, budget, guard);
            }
        }
        @Override public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true; readerLeave(id);
                if (index.bytes == 0 && readers.getOrDefault(id, 0) == 0) {
                    try { var row = mapper.find(id); if (row != null) evict(row); }
                    catch (RuntimeException failure) { logCleanup(failure); }
                }
            }
        }
    }

    public final class OriginalWriter implements AutoCloseable {
        private final Original original;
        private final Path staging;
        private final Runnable guard;
        private long position, budget, blockStart, received;
        private OutputStream output;
        private String claim;
        private OriginalWriter(Original original, long position, Path staging, long budget, Runnable guard) {
            this.original = original; this.position = position; this.staging = staging; this.budget = budget; this.guard = guard;
        }
        public boolean capacityExhausted() {
            synchronized (lock) { replenish(); return staging == null || position < original.total && position % ORIGINAL_BLOCK_BYTES == 0 && budget < Math.min(ORIGINAL_BLOCK_BYTES, original.total - position); }
        }
        private void replenish() {
            if (staging == null || position >= original.total || budget >= Math.min(ORIGINAL_BLOCK_BYTES, original.total - position)) return;
            long desired = Math.min(32L * 1024 * 1024, original.total - position);
            long committed = trim(Math.max(0, desired - budget));
            long extra = Math.min(desired - budget, Math.max(0, settings().path("maxBytes").asLong() - committed));
            try { extra = Math.min(extra, Math.max(0, Files.getFileStore(directory).getUsableSpace() - minimumFreeBytes
                    - Math.max(0, reservations.values().stream().mapToLong(Long::longValue).sum() - stagingBytes.values().stream().mapToLong(Long::longValue).sum()))); }
            catch (IOException failure) { extra = 0; }
            if (extra > 0) { budget += extra; reservations.put(staging, budget + OWNER.length()); }
        }
        public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                if (position % ORIGINAL_BLOCK_BYTES == 0 && staging != null) begin();
                int count = (int) Math.min(length, original.blockEnd(position) - position + 1);
                if (output != null) { output.write(bytes, offset, count); received += count; synchronized (lock) { stagingBytes.merge(staging, (long) count, Long::sum); } }
                position += count; offset += count; length -= count;
                if (output != null && (position % ORIGINAL_BLOCK_BYTES == 0 || position == original.total)) publish();
            }
        }
        private void begin() throws IOException {
            long expected = original.blockEnd(position) - position + 1;
            synchronized (lock) {
                replenish();
                String key = original.id + ":" + position / ORIGINAL_BLOCK_BYTES;
                if (budget < expected || original.present(position) || !originalWriters.add(key)) return;
                claim = key; blockStart = position; received = 0;
                if (!enoughDisk(0)) throw fail(507, "原片缓存磁盘空间不足");
                output = Files.newOutputStream(staging.resolve(original.name(position)), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
        }
        private void publish() throws IOException {
            output.close(); output = null; guard.run();
            synchronized (lock) {
                if (original.closed || !owned(folder(original.id))) throw new IOException("Original cache entry is no longer owned");
                Path source = staging.resolve(original.name(blockStart)), destination = folder(original.id).resolve(original.name(blockStart));
                if (received != original.blockEnd(blockStart) - blockStart + 1) throw new EOFException("Original cache block incomplete");
                // An invalid/truncated owned block stays charged until replacement has succeeded.
                String name = original.name(blockStart);
                long previousBlock = original.index.blocks.getOrDefault(blockStart, 0L);
                long previousResidue = original.index.residues.getOrDefault(name, 0L);
                Files.deleteIfExists(destination);
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
                long nextBytes = original.index.bytes - previousBlock + received;
                long nextResidue = original.index.residueBytes - previousResidue;
                try { repairOriginalBytes(original.id, nextBytes, nextResidue, original.index.bytes, original.index.residueBytes); }
                catch (RuntimeException failure) {
                    long extra = 0;
                    try { Files.deleteIfExists(destination); }
                    catch (IOException retained) { extra = Math.max(0, received - previousBlock - previousResidue); failure.addSuppressed(retained); }
                    long priorExtra = original.index.uncommittedResidues.getOrDefault(name, 0L);
                    orphanBytes = Math.max(0, orphanBytes + extra - priorExtra);
                    if (extra == 0) original.index.uncommittedResidues.remove(name); else original.index.uncommittedResidues.put(name, extra);
                    throw failure;
                }
                original.index.blocks.put(blockStart, received); original.index.bytes = nextBytes;
                original.index.residues.remove(name); original.index.residueBytes = nextResidue;
                Long pending = original.index.uncommittedResidues.remove(name); if (pending != null) orphanBytes = Math.max(0, orphanBytes - pending);
                stagingBytes.computeIfPresent(staging, (key, value) -> Math.max(OWNER.length(), value - received));
                budget -= received; reservations.put(staging, budget + OWNER.length());
                originalWriters.remove(claim); claim = null;
            }
        }
        @Override public void close() {
            try { if (output != null) output.close(); } catch (IOException ignored) { }
            if (staging != null) releaseStaging(staging);
            synchronized (lock) {
                if (claim != null) originalWriters.remove(claim);
                if (staging != null) {
                    reservations.remove(staging); stagingBytes.remove(staging);
                    downloading.computeIfPresent(original.songId, (key, count) -> count == 1 ? null : count - 1);
                }
            }
        }
    }

    /** Download outside the registry lock, verify every byte, then atomically publish and bind. */
    public ObjectNode capture(Map<String, Object> row, String key, Connection connection, JsonNode execution) {
        return capture(row, key, connection, execution, () -> true);
    }
    public ObjectNode capture(Map<String, Object> row, String key, Connection connection, JsonNode execution, BooleanSupplier active) {
        String session = text(row, "id");
        if (!uuid(session) || !execution.path("state").asText().equals("SUCCEEDED")) throw fail(409, "缓存任务尚未完成");
        var capture = new Capture();
        synchronized (lock) {
            if (!active.getAsBoolean()) throw fail(410, "播放会话已结束");
            if (captures.putIfAbsent(session, capture) != null) throw fail(409, "歌曲缓存正在接收");
            downloading.merge(text(row, "song_id"), 1, Integer::sum);
        }
        try { return capture(row, key, connection, execution, active, capture); }
        finally {
            synchronized (lock) { captures.remove(session, capture); downloading.computeIfPresent(text(row, "song_id"), (key2, count) -> count == 1 ? null : count - 1); }
            requestMaintenance();
        }
    }
    /** Stop an in-flight transfer without holding the cache lock while closing its socket. */
    public void cancelCapture(String session) {
        InputStream body;
        synchronized (lock) {
            var capture = captures.get(session);
            if (capture == null) return;
            capture.cancelled = true;
            body = capture.body;
        }
        if (body != null) try { body.close(); } catch (IOException ignored) { /* Transfer cleanup releases its reservation. */ }
    }
    private ObjectNode capture(Map<String, Object> row, String key, Connection connection, JsonNode execution,
            BooleanSupplier active, Capture capture) {
        String session = text(row, "id");
        prepareArtifacts(mapper.byKey(key));
        synchronized (lock) {
            capture.check();
            var prior = mapper.byKey(key);
            if (valid(prior)) { transaction.executeWithoutResult(status -> bind(session, prior, false)); return snapshot(prior, false); }
        }
        String remote = text(row, "execution_id"); if (remote.isBlank()) remote = session;
        if (!uuid(remote)) throw fail(400, "缓存执行引用无效");
        var exported = compute.call(connection, "/internal/v1/transcoding/executions/" + remote + "/export", "GET", null);
        var artifacts = exported.path("artifacts");
        long total = validateExport(artifacts);
        long reserved = Math.addExact(total, OWNER.length());
        String id = UUID.randomUUID().toString(); Path staging = directory.resolve(".partial-" + id);
        synchronized (lock) {
            capture.check();
            ensureDirectory();
            if (reserved > settings().path("maxBytes").asLong()) throw fail(507, "缓存产物超过歌曲缓存容量，继续使用当前播放资源");
            long committed = trim(reserved);
            if (reserved > settings().path("maxBytes").asLong() - committed || !enoughDisk(reserved)) {
                capacityPressure(reserved); throw fail(507, "歌曲缓存容量或磁盘空间不足");
            }
            try {
                Files.createDirectory(staging);
                Files.writeString(staging.resolve(OWNER_FILE), OWNER, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException error) { orphanBytes += OWNER.length(); requestMaintenance(); throw fail(507, "无法创建临时歌曲缓存"); }
            reservations.put(staging, reserved);
            stagingBytes.put(staging, (long) OWNER.length());
        }
        try {
            var fields = artifacts.fields();
            while (fields.hasNext()) {
                capture.check();
                var artifact = fields.next();
                download(connection, remote, artifact.getKey(), artifact.getValue(), staging, capture);
            }
            validatePlaylists(staging, artifacts);
            var manifest = (ObjectNode) artifacts.deepCopy();
            if (!validateFiles(staging, manifest)) throw fail(500, "歌曲缓存发布校验失败");
            synchronized (lock) {
                capture.check();
                if (!active.getAsBoolean()) throw fail(410, "播放会话已结束");
                var prior = mapper.byKey(key);
                if (valid(prior)) { transaction.executeWithoutResult(status -> bind(session, prior, false)); return snapshot(prior, false); }
                if (prior != null) { if (leased(text(prior, "id"))) throw fail(409, "旧缓存仍在使用中"); evict(prior); throw fail(409, "旧缓存清理中，继续使用当前播放资源"); }
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
                try {
                    transaction.executeWithoutResult(status -> { lockSong(text(row, "song_id")); mapper.insert(saved); bind(session, saved, false); });
                    indexedBytes = indexedUsage() + reserved; reservations.remove(staging); stagingBytes.remove(staging);
                    validated.put(id, new CachedArtifacts(manifest, true));
                }
                catch (RuntimeException error) {
                    orphanBytes += reserved; reservations.remove(staging); stagingBytes.remove(staging);
                    requestMaintenance(); throw error;
                }
                return snapshot;
            }
        } finally {
            releaseStaging(staging);
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
    private void download(Connection connection, String remote, String name, JsonNode metadata, Path staging, Capture capture) {
        var response = compute.artifact(connection, "/internal/v1/transcoding/executions/" + remote + "/artifacts/" + name, null);
        long expected = metadata.path("size").asLong();
        try (var input = response.body()) {
            synchronized (lock) { capture.check(); capture.body = input; }
            if (response.statusCode() != 200 || response.headers().firstValueAsLong("Content-Length").orElse(-1) != expected) throw fail(502, "缓存文件长度与清单不符");
            if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(staging)) throw fail(500, "临时缓存目录无效");
            var digest = MessageDigest.getInstance("SHA-256"); long received = 0;
            try (var output = Files.newOutputStream(staging.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536]; int count; long nextDiskCheck = 0;
                while ((count = input.read(buffer)) >= 0) {
                    capture.check();
                    if (count == 0) continue;
                    received += count;
                    if (received > expected) throw fail(502, "缓存文件超过清单长度");
                    if (received >= nextDiskCheck) { if (!enoughDisk(0)) throw fail(507, "缓存磁盘剩余空间不足"); nextDiskCheck = received + ORIGINAL_BLOCK_BYTES; }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count);
                    synchronized (lock) { stagingBytes.merge(staging, (long) count, Long::sum); }
                }
            }
            if (received != expected || !HexFormat.of().formatHex(digest.digest()).equals(metadata.path("sha256").asText())) throw fail(502, "缓存文件摘要或长度校验失败");
        } catch (IOException error) { throw fail(502, "缓存文件下载或保存失败"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { synchronized (lock) { capture.body = null; } }
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
        var initialLease = mapper.lease(sessionId);
        if (initialLease == null) return false;
        prepareArtifacts(mapper.find(text(initialLease, "cache_id")));
        synchronized (lock) {
            var lease = mapper.lease(sessionId); if (lease == null) return false;
            var row = mapper.find(text(lease, "cache_id"));
            if (!ARTIFACT.matcher(name).matches()) throw fail(404, "缓存文件不存在");
            var artifacts = validArtifacts(row);
            if (artifacts == null || !artifacts.has(name) || !validFile(folder(text(row, "id")), name, artifacts))
                throw fail(410, "歌曲缓存已失效，请重新播放");
            if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw fail(400, "仅支持单段字节范围");
            mapper.renew(sessionId, now()); mapper.touch(text(row, "id"), now());
            try {
                channel = Files.newByteChannel(folder(text(row, "id")).resolve(name), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                try {
                    if (channel.size() != artifacts.path(name).path("size").asLong(-1))
                        throw fail(410, "歌曲缓存已失效，请重新播放");
                } catch (IOException | RuntimeException failure) {
                    try { channel.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
                    throw failure;
                }
            } catch (IOException error) { throw fail(410, "歌曲缓存已失效，请重新播放"); }
            cacheId = text(row, "id");
            readerEnter(cacheId, text(row, "song_id"));
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
        } finally { synchronized (lock) { readerLeave(cacheId); } }
    }
}
