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

/** Business authorization, durable offline intent and compatibility version ownership. */
@Service
public class TranscodeService {
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
        var source = sources.get(song.sourceId());
        return com.mynora.singverse.auth.crypto.AuthCrypto.hash(
                MediaSongRepository.fingerprint(song)
                        + ":"
                        + source.updatedAt()
                        + ":"
                        + source.location());
    }

    private ObjectNode input(String id, String songId, Connection c) {
        var song = songs.get(songId);
        var source = sources.get(song.sourceId());
        var node = json.createObjectNode();
        if (source.type().equals("LOCAL")) {
            try {
                Path root = Path.of(source.location()).toRealPath(),
                        file = root.resolve(song.relativePath()).toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file))
                    throw fail(403, "媒体不在允许目录内");
                node.put("path", c.mediaPath(file));
            } catch (IOException e) {
                throw fail(404, "媒体文件不可读取");
            }
        } else node.put("reference", id);
        return node;
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
                        .put("fps", 30)
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
            target.put("fps", Math.max(1, Math.min(30, client.path("max_fps").asDouble(30))));
        }
        for (String key : List.of("audio_id", "subtitle_id"))
            if (body.hasNonNull(key)) {
                if (!body.path(key).asText().matches("stream:[0-9]{1,3}"))
                    throw fail(400, "无效音轨或字幕");
                target.set(key, body.get(key));
            }
        target.put("burn_subtitle", body.path("burn_subtitle").asBoolean(false));
        return target;
    }

    private String insert(
            String songId, String owner, String purpose, JsonNode target, Connection connection) {
        var song = songs.get(songId);
        String id = UUID.randomUUID().toString();
        var source = input(id, songId, connection);
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("song_id", songId);
        row.put("owner_id", owner);
        row.put("purpose", purpose);
        row.put("state", purpose.equals("OFFLINE") ? "QUEUED" : "STARTING");
        row.put("fingerprint", fingerprint(song));
        row.put("node_origin", connection.origin().toString());
        row.put("input_json", source.toString());
        row.put("target_json", target.toString());
        row.put("now", now());
        mapper.insert(row);
        return id;
    }

    public ObjectNode negotiate(
            String songId, String owner, JsonNode body, String directUrl, String prefix) {
        var song = songs.get(songId);
        var client = body.path("client");
        var selected = target(body, "REALTIME");
        ObjectNode cachedVersion = null;
        for (var version : mapper.versions(songId, fingerprint(song))) {
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
                            && t.path("bitrate").asInt() <= selected.path("bitrate").asInt();
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
            request.put("burn_subtitle", body.path("burn_subtitle").asBoolean(false));
            var plan =
                    compute.call(connection, "/internal/v1/transcoding/negotiate", "POST", request);
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
            var remote =
                    compute.call(
                            compute.connection(str(row, "node_origin")),
                            "/internal/v1/transcoding/executions/" + execution(row) + "/lease",
                            "POST",
                            null);
            var merged = (ObjectNode) result.deepCopy();
            merged.setAll((ObjectNode) remote);
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

    public synchronized void cancel(String id, String owner) {
        var row = require(id, owner);
        if (str(row, "state").equals("QUEUED")) {
            mapper.update(id, "CANCELLED", "{}", "", now());
            return;
        }
        compute.call(
                compute.connection(str(row, "node_origin")),
                "/internal/v1/transcoding/executions/" + execution(row) + "/cancel",
                "POST",
                null);
        mapper.update(id, "CANCELLED", str(row, "result_json"), "", now());
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
        var remote =
                compute.artifact(
                        compute.connection(str(row, "node_origin")),
                        "/internal/v1/transcoding/executions/"
                                + execution(row)
                                + "/artifacts/"
                                + name,
                        range);
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
        playback.play(str(row, "song_id"), range, head, response);
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
                    var connection = compute.connection(str(row, "node_origin"));
                    if (str(row, "purpose").equals("REALTIME")) {
                        if (now() - ((Number) row.get("renewed_at")).longValue() > 120000)
                            cancel(id, str(row, "owner_id"));
                        continue;
                    }
                    JsonNode result;
                    if (str(row, "state").equals("QUEUED")) {
                        var body = (ObjectNode) decode(row.get("input_json"));
                        body.put("id", execution(row)).put("fingerprint", str(row, "fingerprint"));
                        body.set("target", decode(row.get("target_json")));
                        result =
                                compute.call(
                                        connection,
                                        "/internal/v1/transcoding/executions",
                                        "POST",
                                        body);
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
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
