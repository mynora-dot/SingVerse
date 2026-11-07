package com.mynora.singverse.media.transcoding;

import static com.mynora.singverse.media.transcoding.TranscodeClient.fail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.MediaPlaybackService;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Business authorization and service-owned decoded caches and compatibility versions. */
@Service
public class TranscodeService {
    @org.springframework.beans.factory.annotation.Autowired private com.mynora.singverse.media.audio.AudioJobService audioJobs;
    @org.springframework.beans.factory.annotation.Autowired private SongCacheService cache;
    private final Set<String> caching = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record CacheRetry(long generation, long at) {}
    private final Map<String, CacheRetry> cacheRetries = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, InputStream> downloading = new HashMap<>();
    private final TranscodeMapper mapper;
    private final TranscodeClient compute;
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final MediaPlaybackService playback;
    private final ObjectMapper json;
    private final Path directory;
    private final AtomicBoolean working = new AtomicBoolean();

    public TranscodeService(
            TranscodeMapper mapper,
            TranscodeClient compute,
            MediaSongRepository songs,
            MediaSourceRepository sources,
            MediaPlaybackService playback,
            ObjectMapper json,
            @Value("${singverse.transcoding.directory:./data/transcoding}") String directory) {
        this.mapper = mapper;
        this.compute = compute;
        this.songs = songs;
        this.sources = sources;
        this.playback = playback;
        this.json = json;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String execution(Map<String, Object> row) {
        return str(row, "execution_id");
    }

    private static String str(Map<String, Object> r, String k) {
        return Objects.toString(r.get(k), "");
    }

    private JsonNode decode(Object value) {
        try {
            return json.readTree(value.toString());
        } catch (Exception e) {
            throw fail(500, "转码记录无效");
        }
    }

    public Map<String, Object> require(String id, String owner) {
        if (!id.matches("[a-f0-9-]{36}")) throw fail(404, "播放资源不存在");
        var row = mapper.find(id);
        if (row == null || !str(row, "owner_id").equals(owner) && !owner.equals("admin"))
            throw fail(404, "播放资源不存在");
        return row;
    }

    private String fingerprint(com.mynora.singverse.media.dto.MediaSongDtos.Song song) {
        return com.mynora.singverse.media.model.PlaybackFingerprint.of(song, sources.get(song.sourceId()));
    }

    private ObjectNode input(String id) {
        return json.createObjectNode().put("reference", id);
    }

    private static boolean contains(JsonNode values, String value) {
        if (!values.isArray()) return false;
        for (var item : values) if (item.asText().equals(value)) return true;
        return false;
    }

    private ObjectNode target(JsonNode body, String purpose) {
        String quality = body.path("quality").asText("auto");
        int width = 1920, height = 1080, bitrate = 8000000;
        switch (quality) {
            case "auto", "1080p" -> {}
            case "720p" -> {
                width = 1280;
                height = 720;
                bitrate = 4000000;
            }
            case "480p" -> {
                width = 854;
                height = 480;
                bitrate = 1500000;
            }
            default -> throw fail(400, "未知画质");
        }
        double start = body.path("start").asDouble(0);
        if (!Double.isFinite(start)
                || start < 0
                || start > 86400
                || purpose.equals("OFFLINE") && start != 0) throw fail(400, "播放位置无效");
        var client = body.path("client");
        if (quality.equals("auto") && purpose.equals("REALTIME")) {
            width = Math.max(2, Math.min(7680, client.path("max_width").asInt(width)));
            height = Math.max(2, Math.min(4320, client.path("max_height").asInt(height)));
            bitrate = Math.max(64000, Math.min(100000000, client.path("max_bitrate").asInt(bitrate)));
        }
        String codec = body.path("codec").asText("h264");
        if (!List.of("h264", "hevc", "av1").contains(codec)) throw fail(400, "未知视频编码");
        if (purpose.equals("REALTIME")
                && !codec.equals("h264")
                && (!contains(client.path("video"), codec)
                        || !client.path("fmp4").asBoolean(false)))
            throw fail(422, "该终端未报告此编码和 fMP4 播放能力");
        var target =
                json.createObjectNode()
                        .put("purpose", purpose)
                        .put("mode", "TRANSCODE")
                        .put("codec", codec)
                        .put("width", width)
                        .put("height", height)
                        .put("bitrate", bitrate)
                        .put("audio_bitrate", 192000)
                        .put("fps", quality.equals("auto") && purpose.equals("REALTIME")
                                ? Math.max(1, Math.min(120, client.path("max_fps").asDouble(30))) : 30)
                        .put("start", start)
                        .put("fmp4", client.path("fmp4").asBoolean(false));
        if (!client.isMissingNode()) {
            target.put(
                    "width", Math.max(2, Math.min(width, client.path("max_width").asInt(width))));
            target.put(
                    "height",
                    Math.max(2, Math.min(height, client.path("max_height").asInt(height))));
            target.put(
                    "bitrate",
                    Math.max(64000, Math.min(bitrate, client.path("max_bitrate").asInt(bitrate))));
            target.put("fps", Math.max(1, Math.min(target.path("fps").asDouble(), client.path("max_fps").asDouble(30))));
        }
        if (codec.equals("h264")) constrainAvc(target, client);
        for (String key : List.of("audio_id", "subtitle_id"))
            if (body.hasNonNull(key)) {
                if (!body.path(key).asText().matches("stream:[0-9]{1,3}"))
                    throw fail(400, "无效音轨或字幕");
                target.set(key, body.get(key));
            }
        target.put("burn_subtitle", body.path("burn_subtitle").asBoolean(false));
        return target;
    }

    /** Use explicit decoder evidence; omitted profile/level keeps the existing encoder defaults. */
    private static void constrainAvc(ObjectNode target, JsonNode client) {
        var profiles = client.path("profiles").path("h264");
        String profile = contains(profiles, "High") ? "high" : contains(profiles, "Main") ? "main"
                : contains(profiles, "Baseline") || contains(profiles, "Constrained Baseline") ? "baseline" : "";
        if (!profile.isEmpty()) target.put("profile", profile);
        int level = client.path("levels").path("h264").asInt();
        // H.264 MaxFS, MaxMBPS and baseline/main MaxBR, indexed by ffprobe's level units.
        int[] limits = switch (level) {
            case 9 -> new int[] {99,1485,128000};
            case 10 -> new int[] {99,1485,64000};
            case 11 -> new int[] {396,3000,192000};
            case 12 -> new int[] {396,6000,384000};
            case 13 -> new int[] {396,11880,768000};
            case 20 -> new int[] {396,11880,2000000};
            case 21 -> new int[] {792,19800,4000000};
            case 22 -> new int[] {1620,20250,4000000};
            case 30 -> new int[] {1620,40500,10000000};
            case 31 -> new int[] {3600,108000,14000000};
            case 32 -> new int[] {5120,216000,20000000};
            case 40 -> new int[] {8192,245760,20000000};
            case 41 -> new int[] {8192,245760,50000000};
            case 42 -> new int[] {8704,522240,50000000};
            case 50 -> new int[] {22080,589824,135000000};
            case 51 -> new int[] {36864,983040,240000000};
            case 52 -> new int[] {36864,2073600,240000000};
            case 60 -> new int[] {139264,4177920,240000000};
            case 61 -> new int[] {139264,8355840,480000000};
            case 62 -> new int[] {139264,16711680,800000000};
            default -> null;
        };
        if (limits == null) return;
        target.put("level", level);
        int width = target.path("width").asInt(), height = target.path("height").asInt();
        if ((long) ((width + 15) / 16) * ((height + 15) / 16) > limits[0]) {
            width = Math.max(2, Math.min(width, Math.min(limits[0] * 16, (int) Math.sqrt(limits[0] * (double) width / height) * 16)));
            height = Math.max(2, Math.min(height, limits[0] / ((width + 15) / 16) * 16));
            target.put("width", width).put("height", height);
        }
        int macroblocks = ((width + 15) / 16) * ((height + 15) / 16);
        target.put("fps", Math.min(target.path("fps").asDouble(), limits[1] / (double) macroblocks));
        long bitrate = Math.round(limits[2] * (profile.equals("high") ? 1.25 : 1));
        target.put("bitrate", Math.min(target.path("bitrate").asLong(), bitrate));
    }

    private String insert(
            String songId, String owner, String purpose, JsonNode target, Connection connection) {
        return insert(UUID.randomUUID().toString(), songId, owner, purpose, target, connection);
    }

    private String insert(String id,
            String songId, String owner, String purpose, JsonNode target, Connection connection) {
        var song = songs.get(songId);
        var source = input(id);
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("song_id", songId);
        row.put("owner_id", owner);
        row.put("purpose", purpose);
        row.put("state", purpose.equals("OFFLINE") ? "QUEUED" : "STARTING");
        row.put("fingerprint", fingerprint(song));
        row.put("node_origin", connection == null ? "" : connection.origin().toString());
        row.put("input_json", source.toString());
        row.put("target_json", target.toString());
        row.put("now", now());
        mapper.insert(row);
        return id;
    }

    public ObjectNode negotiate(String songId,String owner,JsonNode body,String directUrl,String prefix){
        var audio=audioJobs.status(songId);
        var request=body.deepCopy();
        String requested=body.path("audioRole").asText();
        if(request instanceof ObjectNode object && Set.of("original","backing").contains(requested)){
            String selected=audio.path(requested.equals("original")?"originalId":"backingId").asText();
            if(!selected.isBlank())object.put("audio_id",selected);
            object.remove("audioRole");
        }
        var result=negotiateMedia(songId,owner,request,directUrl,prefix);
        result.set("audioRoles",audioJobs.status(songId));
        if(requested.equals("backing") && audio.has("backingResource")){
            String base=owner.equals("admin")?"/api/v1/media-songs/":"/api/v1/tv/audio-roles/songs/";
            result.put("backingUrl",base+songId+"/accompaniment/"+audio.path("backingResource").path("jobId").asText());
        }
        return result;
    }
    public ObjectNode negotiateMedia(
            String songId, String owner, JsonNode body, String directUrl, String prefix) {
        var song = songs.get(songId);
        var client = body.path("client");
        var selected = target(body, "REALTIME");
        String mediaFingerprint = fingerprint(song);
        String cacheKey = cache.key(songId, mediaFingerprint, selected, client,
                body.path("force").asBoolean(false), body.hasNonNull("codec"));
        String cachedSession = UUID.randomUUID().toString();
        var cachedTarget = selected;
        ObjectNode cached = null;
        if (selected.path("start").asDouble() > 0) {
            // A full-song cache serves seeks with its original offset; partial caches keep exact keys.
            var wholeTarget = selected.deepCopy().put("start", 0.0);
            String wholeKey = cache.key(songId, mediaFingerprint, wholeTarget, client,
                    body.path("force").asBoolean(false), body.hasNonNull("codec"));
            cached = cache.acquire(wholeKey, cachedSession);
            if (cached != null && (!cached.path("offset").isNumber() || cached.path("offset").asDouble() != 0.0)) {
                cache.release(cachedSession);
                cached = null;
            }
            if (cached != null) {
                cacheKey = wholeKey;
                cachedTarget = wholeTarget;
            }
        }
        if (cached == null) cached = cache.acquire(cacheKey, cachedSession);
        if (cached != null) {
            try {
                insert(cachedSession, songId, owner, "REALTIME", cachedTarget, null);
                cached.put("id", cachedSession).put("cacheKey", cacheKey).put("cacheHit", true).put("computeReleased", true);
                if (mapper.saveActiveCache(cachedSession, cached.toString(), now()) != 1)
                    throw fail(410, "播放资源已结束");
                return view(cachedSession, owner, prefix);
            } catch (RuntimeException error) {
                cache.release(cachedSession);
                throw error;
            }
        }
        ObjectNode cachedVersion = null;
        if (!body.path("force").asBoolean(false)) for (var version : mapper.versions(songId, mediaFingerprint)) {
            if (body.hasNonNull("audio_id")
                    || body.hasNonNull("subtitle_id")
                    || body.path("burn_subtitle").asBoolean()) break;
            var t = decode(version.get("target_json"));
            boolean compatible =
                    contains(client.path("video"), t.path("codec").asText("h264"))
                            && contains(client.path("audio"), "aac")
                            && contains(client.path("containers"), "mp4")
                            && t.path("width").asInt() <= selected.path("width").asInt()
                            && t.path("height").asInt() <= selected.path("height").asInt()
                            && t.path("bitrate").asInt() <= selected.path("bitrate").asInt()
                            && versionVideoCompatible(t, decode(version.get("result_json")), client, selected);
            if (compatible && Files.isRegularFile(directory.resolve(str(version, "id") + ".mp4"))) {
                // Each device receives its own authorized lease, rather than another device's
                // session ID.
                cachedVersion =
                        json.createObjectNode()
                                .put("mode", "VERSION")
                                .put("status", "READY")
                                .put(
                                        "url",
                                        prefix + "/versions/" + str(version, "id") + "/" + songId)
                                .put("offset", 0);
                break;
            }
        }
        Connection connection;
        try {
            connection = compute.connection();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (cachedVersion != null) return cachedVersion;
            if (body.path("force").asBoolean(false)) throw e;
            return direct(directUrl, "COMPUTE_UNAVAILABLE");
        }
        String id = insert(songId, owner, "REALTIME", selected, connection);
        try {
            var request = (ObjectNode) decode(mapper.find(id).get("input_json"));
            var effectiveClient =
                    client.isObject() ? ((ObjectNode) client).deepCopy() : json.createObjectNode();
            effectiveClient
                    .put("max_width", selected.path("width").asInt())
                    .put("max_height", selected.path("height").asInt())
                    .put("max_bitrate", selected.path("bitrate").asInt())
                    .put("max_fps", selected.path("fps").asDouble());
            request.set("client", effectiveClient);
            request.put("fingerprint", mediaFingerprint);
            request.put("burn_subtitle", body.path("burn_subtitle").asBoolean(false));
            var plan =
                    compute.call(connection, "/internal/v1/transcoding/negotiate", "POST", request);
            audioJobs.observe(songId,plan.path("media"));
            if (plan.path("mode").asText().equals("DIRECT")
                    && !body.path("force").asBoolean(false)
                    && !body.path("burn_subtitle").asBoolean(false)
                    && !body.hasNonNull("audio_id")
                    && !body.hasNonNull("subtitle_id")
                    && !body.hasNonNull("codec")) {
                mapper.update(id, "DIRECT", plan.toString(), "", now());
                return direct(directUrl, "").set("media", plan.path("media"));
            }
            if (cachedVersion != null && !body.path("force").asBoolean(false)) {
                mapper.update(id, "DIRECT", plan.toString(), "", now());
                return cachedVersion;
            }
            if (!client.path("hls").asBoolean(true))
                throw fail(422, "当前终端需要转换，但不支持 HLS；请先生成离线兼容版本");
            if (!body.path("force").asBoolean(false))
                selected.put(
                        "mode",
                        plan.path("mode").asText().equals("DIRECT")
                                ? "REMUX"
                                : plan.path("mode").asText());
            var streams = plan.path("media").path("streams");
            if (selected.path("mode").asText().equals("REMUX"))
                for (var stream : streams) {
                    if (stream.path("kind").asText().equals("audio")
                            && !List.of("aac", "mp3", "ac3", "eac3")
                                    .contains(stream.path("codec").asText()))
                        selected.put("mode", "AUDIO_TRANSCODE");
                }
            for (var stream : streams)
                if (stream.path("kind").asText().equals("video")
                        && !selected.path("mode").asText().equals("TRANSCODE")
                        && !List.of("h264", "hevc").contains(stream.path("codec").asText()))
                    selected.put("mode", "TRANSCODE");
            if (body.hasNonNull("codec"))
                for (var stream : streams)
                    if (stream.path("kind").asText().equals("video")
                            && !stream.path("codec")
                                    .asText()
                                    .equals(selected.path("codec").asText()))
                        selected.put("mode", "TRANSCODE");
            for (var stream : streams)
                if (stream.path("kind").asText().equals("video")
                        && stream.path("hdr").asText().equals("DOVI")
                        && !selected.path("fmp4").asBoolean(false))
                    selected.put("mode", "TRANSCODE");
            mapper.plan(id, selected.toString());
            cache.assertCapacity();
            // Persist the exact mode used by retries and restart recovery.
            var row = mapper.find(id);
            var start = (ObjectNode) decode(row.get("input_json"));
            start.put("id", id).put("fingerprint", str(row, "fingerprint"));
            start.set("target", selected);
            JsonNode execution = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    execution =
                            compute.call(
                                    connection,
                                    "/internal/v1/transcoding/executions",
                                    "POST",
                                    start);
                    break;
                } catch (org.springframework.web.server.ResponseStatusException busy) {
                    if (busy.getStatusCode().value() != 429 || attempt == 19) throw busy;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw fail(503, "播放准备已中断");
                    }
                }
            }
            ((ObjectNode) execution).set("media", plan.path("media"));
            ((ObjectNode) execution).set("reasons", plan.path("reasons"));
            ((ObjectNode) execution).put("cacheKey", cacheKey);
            mapper.update(id, "RUNNING", execution.toString(), "", now());
            return view(id, owner, prefix);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            mapper.update(id, "FAILED", "{}", Objects.toString(e.getReason(), "转码失败"), now());
            if (List.of(502, 503).contains(e.getStatusCode().value())
                    && !body.path("force").asBoolean(false)
                    && !body.hasNonNull("audio_id")
                    && !body.hasNonNull("subtitle_id"))
                return cachedVersion != null
                        ? cachedVersion
                        : direct(directUrl, "COMPUTE_UNAVAILABLE");
            throw e;
        }
    }

    private static boolean versionVideoCompatible(JsonNode target, JsonNode result, JsonNode client, JsonNode selected) {
        String targetCodec = target.path("codec").asText("h264");
        boolean measuredRequired = client.path("profiles").has(targetCodec)
                || client.path("levels").has(targetCodec)
                || client.hasNonNull("max_fps") || client.hasNonNull("max_bit_depth");
        JsonNode video = null;
        for (var stream : result.path("output").path("media").path("streams"))
            if (stream.path("kind").asText().equals("video")) { video = stream; break; }
        if (video == null) {
            // Older outputs without measured decoder constraints cannot establish
            // compatibility for a terminal explicitly reporting profile/level limits.
            return !measuredRequired
                    && target.path("fps").asDouble(30) <= selected.path("fps").asDouble();
        }
        if (measuredRequired && (video.path("fps").asDouble() <= 0 || video.path("bit_depth").asInt() <= 0))
            return false;
        String codec = video.path("codec").asText();
        var profiles = client.path("profiles").path(codec);
        int level = client.path("levels").path(codec).asInt();
        String profile = video.path("profile").asText();
        boolean profileSupported = contains(profiles, profile)
                || profile.equals("Constrained Baseline") && contains(profiles, "Baseline")
                || profile.equals("Constrained High") && contains(profiles, "High");
        return (!profiles.isArray() || profiles.isEmpty() || profileSupported)
                && (level == 0 || video.path("level").asInt() > 0
                        && avcLevelRank(video.path("level").asInt()) <= avcLevelRank(level))
                && video.path("fps").asDouble() <= selected.path("fps").asDouble()
                && video.path("bit_depth").asInt(8) <= client.path("max_bit_depth").asInt(8);
    }

    private static double avcLevelRank(int level) { return level == 9 ? 10.5 : level; }

    private ObjectNode direct(String url, String reason) {
        return json.createObjectNode()
                .put("mode", "DIRECT")
                .put("status", "READY")
                .put("url", url)
                .put("reason", reason)
                .put("offset", 0);
    }

    public ObjectNode view(String id, String owner, String prefix) {
        var row = require(id, owner);
        if (!str(row, "fingerprint").equals(fingerprint(songs.get(str(row, "song_id")))))
            throw fail(409, "媒体已变化，请重建播放资源");
        JsonNode result = decode(row.get("result_json"));
        if (List.of("STARTING", "RUNNING").contains(str(row, "state"))) {
            var merged = (ObjectNode) result.deepCopy();
            var local = str(row, "purpose").equals("REALTIME") ? cache.renew(id) : null;
            if (local != null) {
                boolean hit = merged.path("cacheHit").asBoolean();
                merged.setAll(local);
                merged.put("id", id).put("cacheHit", hit);
                if (!result.hasNonNull("cacheId") && mapper.saveActiveCache(id, merged.toString(), now()) != 1) {
                    cache.release(id);
                    throw fail(410, "播放资源已结束");
                }
            } else {
                if (result.hasNonNull("cacheId")) throw fail(410, "缓存已清理，请重新开始播放");
                var remote = compute.call(compute.connection(str(row, "node_origin")),
                        "/internal/v1/transcoding/executions/" + execution(row) + "/lease", "POST", null);
                merged.setAll((ObjectNode) remote);
                // The scheduler transfers completed media. Playback leases must never
                // wait for a whole-song cache download before exposing ready segments.
            }
            result = merged;
            mapper.renew(id, now());
        }
        if (List.of("FAILED", "CANCELLED").contains(str(row, "state"))) {
            ((ObjectNode) result).put("ready", false).put("state", str(row, "state"));
        }
        var out =
                json.createObjectNode()
                        .put("id", id)
                        .put("songId", str(row, "song_id"))
                        .put("mode", str(row, "purpose").equals("OFFLINE") ? "VERSION" : "HLS")
                        .put(
                                "status",
                                result.path("ready").asBoolean() ? "READY" : str(row, "state"))
                        .put("state", result.path("state").asText(str(row, "state")))
                        .put("offset", result.path("offset").asDouble())
                        .put("error", result.path("error").asText(str(row, "error")));
        if (result.path("subtitle").asBoolean())
            out.put("subtitleUrl", prefix + "/" + id + "/artifacts/subtitle.vtt");
        out.set("execution", result);
        out.put(
                "url",
                prefix
                        + "/"
                        + id
                        + "/artifacts/"
                        + (str(row, "purpose").equals("OFFLINE") ? "output.mp4" : "master.m3u8"));
        return out;
    }

    /** Publish verified HLS on the service node before releasing compute's temporary output. */
    private ObjectNode cacheCompleted(Map<String, Object> row, ObjectNode execution) {
        String id = str(row, "id");
        var retry = cacheRetries.get(id);
        if (retry != null && retry.generation() == cache.cleanupGeneration() && now() < retry.at()) return execution;
        if (!str(row, "purpose").equals("REALTIME") || execution.hasNonNull("cacheId")
                || !execution.path("state").asText().equals("SUCCEEDED")
                || execution.path("cacheKey").asText().isBlank() || !caching.add(id)) return execution;
        try {
            var connection = compute.connection(str(row, "node_origin"));
            var saved = cache.capture(row, execution.path("cacheKey").asText(), connection, execution, () -> {
                var latest = mapper.find(id);
                return latest != null && Set.of("STARTING", "RUNNING").contains(str(latest, "state"));
            });
            saved.put("id", id).put("cacheKey", execution.path("cacheKey").asText()).put("cacheHit", false)
                    .put("computeReleased", false);
            if (mapper.saveActiveCache(id, saved.toString(), now()) != 1) cache.release(id);
            acknowledgeCached(row, saved);
            cacheRetries.remove(id);
            return saved;
        } catch (RuntimeException error) {
            // A failed cache transfer must not interrupt the current temporary playback.
            org.slf4j.LoggerFactory.getLogger(TranscodeService.class).warn("Song cache transfer deferred for execution {}", id);
            cacheRetries.put(id, new CacheRetry(cache.cleanupGeneration(), now() + 30_000));
            if (cacheRetries.size() > 1024) cacheRetries.entrySet().removeIf(entry -> entry.getValue().at() < now());
            return execution;
        } finally {
            caching.remove(id);
        }
    }

    private void acknowledgeCached(Map<String, Object> row, ObjectNode result) {
        if (result.path("computeReleased").asBoolean() || str(row, "node_origin").isBlank()) return;
        try {
            compute.call(compute.connection(str(row, "node_origin")),
                    "/internal/v1/transcoding/executions/" + execution(row) + "/acknowledge", "POST", null);
            result.put("computeReleased", true);
            if (mapper.saveActiveCache(str(row, "id"), result.toString(), now()) != 1)
                cache.release(str(row, "id"));
        } catch (RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(TranscodeService.class).warn("Temporary compute output release deferred for execution {}", str(row, "id"));
        }
    }

    public JsonNode hardwareSettings() {
        return compute.call(
                compute.connection(), "/internal/v1/transcoding/hardware-settings", "GET", null);
    }

    public JsonNode hardwareSettings(JsonNode body, boolean test) {
        return compute.call(
                compute.connection(),
                "/internal/v1/transcoding/hardware-settings" + (test ? "/test" : ""),
                "POST",
                body);
    }

    public JsonNode capabilities() {
        return compute.call(
                compute.connection(), "/internal/v1/transcoding/capabilities", "GET", null);
    }

    public List<ObjectNode> tasks() {
        return mapper.list().stream()
                .filter(r -> str(r, "purpose").equals("OFFLINE"))
                .map(
                        r -> {
                            var out =
                                    json.createObjectNode()
                                            .put("id", str(r, "id"))
                                            .put("songId", str(r, "song_id"))
                                            .put("state", str(r, "state"))
                                            .put("error", str(r, "error"));
                            out.set("execution", decode(r.get("result_json")));
                            return out;
                        })
                .toList();
    }

    public synchronized List<String> offline(JsonNode body) {
        var ids = body.path("songIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size() > 100) throw fail(400, "每批请选择 1～100 首歌曲");
        var connection = compute.connection();
        var target = target(body, "OFFLINE");
        var result = new ArrayList<String>();
        for (var id : ids) {
            songs.get(id.asText());
        }
        for (var id : ids) {
            String fingerprint = fingerprint(songs.get(id.asText()));
            var prior =
                    mapper.list().stream()
                            .filter(
                                    row ->
                                            str(row, "song_id").equals(id.asText())
                                                    && str(row, "fingerprint").equals(fingerprint)
                                                    && str(row, "purpose").equals("OFFLINE")
                                                    && str(row, "target_json")
                                                            .equals(target.toString())
                                                    && str(row, "node_origin")
                                                            .equals(connection.origin().toString())
                                                    && str(row, "pipeline_version").equals("2")
                                                    && List.of(
                                                                    "QUEUED",
                                                                    "RUNNING",
                                                                    "STARTING",
                                                                    "SUCCEEDED")
                                                            .contains(str(row, "state")))
                            .findFirst();
            result.add(
                    prior.isPresent()
                            ? str(prior.get(), "id")
                            : insert(id.asText(), "admin", "OFFLINE", target, connection));
        }
        return result;
    }

    public void cancel(String id, String owner) {
        Map<String, Object> row;
        InputStream active;
        synchronized (this) {
            row = require(id, owner);
            mapper.update(id, "CANCELLED", str(row, "result_json"), "", now());
            active = downloading.remove(id);
        }
        cache.cancelCapture(id);
        cache.release(id);
        if (active != null) try { active.close(); } catch (IOException ignored) { /* Download removes its partial file. */ }
        if (!str(row, "node_origin").isBlank() && !decode(row.get("result_json")).path("computeReleased").asBoolean()) {
            try { cancelRemote(row, compute.connection(str(row, "node_origin"))); }
            catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(TranscodeService.class).warn("Compute cancellation deferred for execution {}", execution(row));
            }
        }
    }

    private void cancelRemote(Map<String, Object> row, Connection connection) {
        compute.call(connection, "/internal/v1/transcoding/executions/" + execution(row) + "/cancel", "POST", null);
    }

    public synchronized void delete(String id) {
        var row = require(id, "admin");
        if (!List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(str(row, "state")))
            throw fail(409, "请先取消转换任务");
        if (now() - ((Number) row.get("renewed_at")).longValue() < 120000)
            throw fail(409, "版本正在使用，请稍后删除");
        try {
            Files.deleteIfExists(directory.resolve(id + ".mp4"));
        } catch (IOException e) {
            throw fail(500, "版本删除失败");
        }
        mapper.delete(id);
    }

    public void version(
            String id, String song, HttpServletResponse response, String range, boolean head)
            throws IOException {
        var row = require(id, "admin");
        if (!str(row, "song_id").equals(song)
                || !str(row, "purpose").equals("OFFLINE")
                || !str(row, "state").equals("SUCCEEDED")
                || !str(row, "fingerprint").equals(fingerprint(songs.get(song))))
            throw fail(404, "兼容版本已失效");
        mapper.renew(id, now());
        playback.playDerived(directory, id + ".mp4", range, head, response);
    }

    public void artifact(
            String id, String owner, String name, HttpServletResponse response, String range)
            throws IOException {
        var row = require(id, owner);
        if (!name.matches(
                "(master\\.m3u8|stream_[a-zA-Z0-9]+\\.m3u8|stream_[a-zA-Z0-9]+_[0-9]+\\.(ts|m4s)|init_[a-zA-Z0-9]+\\.mp4|output\\.mp4|subtitle\\.vtt)"))
            throw fail(404, "播放资源不存在");
        if (List.of("CANCELLED", "FAILED").contains(str(row, "state"))) throw fail(410, "播放资源已结束");
        if (!str(row, "fingerprint").equals(fingerprint(songs.get(str(row, "song_id")))))
            throw fail(409, "源媒体已变化");
        mapper.renew(id, now());
        if (str(row, "purpose").equals("OFFLINE")
                && name.equals("output.mp4")
                && str(row, "state").equals("SUCCEEDED")) {
            version(id, str(row, "song_id"), response, range, false);
            return;
        }
        if (cache.serve(id, name, range, false, response)) return;
        if (decode(row.get("result_json")).hasNonNull("cacheId"))
            throw fail(410, "缓存已清理，请重新开始播放");
        java.net.http.HttpResponse<InputStream> remote;
        try {
            remote = compute.artifact(
                    compute.connection(str(row, "node_origin")),
                    "/internal/v1/transcoding/executions/" + execution(row) + "/artifacts/" + name,
                    range);
        } catch (org.springframework.web.server.ResponseStatusException unavailable) {
            // A concurrent publish may have released compute's copy after the first local lookup.
            if (!response.isCommitted() && cache.serve(id, name, range, false, response)) return;
            throw unavailable;
        }
        try (var input = remote.body()) {
            response.setStatus(remote.statusCode());
            response.setHeader("Cache-Control", "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            for (var h :
                    List.of("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"))
                remote.headers().firstValue(h).ifPresent(v -> response.setHeader(h, v));
            input.transferTo(response.getOutputStream());
        }
    }

    public void source(
            String id, String token, String range, boolean head, HttpServletResponse response)
            throws IOException {
        var row = sourceRow(id, token);
        playback.play(str(row, "song_id"), range, head, response, () -> sourceRow(id, token));
    }

    private Map<String, Object> sourceRow(String id, String token) {
        var connection = compute.connection();
        if (token == null
                || !java.security.MessageDigest.isEqual(
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        ("Bearer " + connection.token())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw fail(401, "内部认证失败");
        var row = require(id, "admin");
        if (!List.of("STARTING", "RUNNING", "QUEUED").contains(str(row, "state"))
                || !str(row, "node_origin").equals(connection.origin().toString()))
            throw fail(410, "媒体读取授权已结束");
        if (!str(row, "fingerprint").equals(fingerprint(songs.get(str(row, "song_id")))))
            throw fail(409, "媒体已变化");
        return row;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000, scheduler = "transcodeScheduler")
    public void tick() {
        if (!working.compareAndSet(false, true)) return;
        try {
            for (var row : mapper.pending()) {
                String id = str(row, "id");
                try {
                    if (!str(row, "pipeline_version").equals("2")) {
                        var oldConnection = compute.connection(str(row, "node_origin"));
                        if (!str(row, "state").equals("QUEUED")) {
                            try {
                                compute.call(
                                        oldConnection,
                                        "/internal/v1/transcoding/executions/"
                                                + execution(row)
                                                + "/cancel",
                                        "POST",
                                        null);
                            } catch (
                                    org.springframework.web.server.ResponseStatusException
                                            missing) {
                                if (missing.getStatusCode().value() != 404) throw missing;
                            }
                        }
                        if (str(row, "purpose").equals("REALTIME"))
                            mapper.update(id, "FAILED", "{}", "转码流水线已升级，请重建播放会话", now());
                        else mapper.requeue(id, UUID.randomUUID().toString(), now());
                        continue;
                    }
                    if (!str(row, "fingerprint")
                            .equals(fingerprint(songs.get(str(row, "song_id"))))) {
                        mapper.update(id, "FAILED", "{}", "源媒体已变化", now());
                        continue;
                    }
                    if (str(row, "purpose").equals("REALTIME")) {
                        var result = (ObjectNode) decode(row.get("result_json"));
                        if (result.hasNonNull("cacheId")) acknowledgeCached(row, result);
                        else if (result.hasNonNull("cacheKey")) {
                            var remote = compute.call(compute.connection(str(row, "node_origin")),
                                    "/internal/v1/transcoding/executions/" + execution(row), "GET", null);
                            result.setAll((ObjectNode) remote);
                            cacheCompleted(row, result);
                        }
                        // Transfers may outlast this pending snapshot; expire against the latest
                        // lease in the database so a player renewing during capture stays active.
                        if (mapper.expireRealtime(id, now() - 120000, now()) == 1)
                            cancel(id, str(row, "owner_id"));
                        continue;
                    }
                    var connection = compute.connection(str(row, "node_origin"));
                    JsonNode result;
                    if (str(row, "state").equals("QUEUED")) {
                        var body = (ObjectNode) decode(row.get("input_json"));
                        body.put("id", execution(row)).put("fingerprint", str(row, "fingerprint"));
                        body.set("target", decode(row.get("target_json")));
                        try { result = compute.call(
                                        connection,
                                        "/internal/v1/transcoding/executions",
                                        "POST",
                                        body); }
                        finally {
                            var latest = mapper.find(id);
                            if (latest != null && str(latest, "state").equals("CANCELLED"))
                                try { cancelRemote(row, connection); } catch (RuntimeException ignored) {}
                        }
                    } else
                        result =
                                compute.call(
                                        connection,
                                        "/internal/v1/transcoding/executions/" + execution(row),
                                        "GET",
                                        null);
                    String state = result.path("state").asText("FAILED");
                    if (state.equals("SUCCEEDED")) {
                        download(row, connection, result);
                        compute.call(
                                connection,
                                "/internal/v1/transcoding/executions/"
                                        + execution(row)
                                        + "/acknowledge",
                                "POST",
                                null);
                    }
                    if (state.equals("CANCELLED")
                            || result.path("error")
                                    .asText()
                                    .equals("NODE_RESTARTED")) { // A realtime request preempted
                        // this offline execution. Keep
                        // intent with a fresh ID.
                        mapper.requeue(id, UUID.randomUUID().toString(), now());
                    } else
                        mapper.update(
                                id,
                                state.equals("ACCEPTED") ? "RUNNING" : state,
                                result.toString(),
                                result.path("error").asText(),
                                now());
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    if (e.getStatusCode().value() == 404)
                        mapper.requeue(id, UUID.randomUUID().toString(), now());
                } catch (Exception e) {
                    mapper.update(id, "FAILED", "{}", "转换或保存失败", now());
                }
            }
        } finally {
            working.set(false);
        }
    }

    private void download(Map<String, Object> row, Connection connection, JsonNode result)
            throws IOException {
        String id = str(row, "id");
        Files.createDirectories(directory);
        Path temp = directory.resolve(id + ".partial"), target = directory.resolve(id + ".mp4");
        if (Files.isRegularFile(target)) return;
        var remote =
                compute.artifact(
                        connection,
                        "/internal/v1/transcoding/executions/"
                                + execution(row)
                                + "/artifacts/output.mp4",
                        null);
        try (var input = remote.body();
                var output = Files.newOutputStream(temp)) {
            synchronized (this) { checkDownload(row); downloading.put(id, input); }
            if (remote.statusCode() != 200) throw new IOException("Unexpected artifact response");
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            long total = 0;
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 10L * 1024 * 1024 * 1024
                        || Files.getFileStore(directory).getUsableSpace() < 2L * 1024 * 1024 * 1024)
                    throw new IOException("Storage budget exceeded");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            long expected = remote.headers().firstValueAsLong("Content-Length").orElse(total);
            if (total == 0
                    || total != expected
                    || total != result.path("output").path("size").asLong()
                    || !java.util.HexFormat.of()
                            .formatHex(digest.digest())
                            .equals(result.path("output").path("sha256").asText()))
                throw new IOException("Incomplete artifact");
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temp);
            throw error;
        } finally { synchronized (this) { downloading.remove(id); } }
        try {
            synchronized (this) {
                checkDownload(row);
                if (!str(row, "fingerprint").equals(fingerprint(songs.get(str(row, "song_id")))))
                    throw fail(409, "源媒体已变化");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally { Files.deleteIfExists(temp); }
    }
    private void checkDownload(Map<String, Object> row) throws InterruptedIOException {
        var latest = mapper.find(str(row, "id"));
        if (latest == null || Set.of("CANCELLED", "DELETING").contains(str(latest, "state"))
                || !execution(latest).equals(execution(row))) throw new InterruptedIOException("Transcode download cancelled");
    }
}
