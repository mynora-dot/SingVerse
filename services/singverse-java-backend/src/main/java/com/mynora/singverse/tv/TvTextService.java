package com.mynora.singverse.tv;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.webdav.metadata.WebDavMetadataMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads same-stem sidecars and existing embedded metadata for an authorized song. Never writes media.
 */
@Service
public class TvTextService {
    private static final int LIMIT = 131072;
    private static final java.util.regex.Pattern LRC_STAMP =
            java.util.regex.Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.,]\\d{1,3})?\\]");
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final AuthCrypto crypto;
    private final WebDavMetadataMapper embedded;
    private final MediaSongMapper storedSongs;
    private final ObjectMapper json;

    public TvTextService(
            MediaSongRepository songs, MediaSourceRepository sources, AuthCrypto crypto,
            WebDavMetadataMapper embedded, MediaSongMapper storedSongs, ObjectMapper json) {
        this.songs = songs;
        this.sources = sources;
        this.crypto = crypto;
        this.embedded = embedded;
        this.storedSongs = storedSongs;
        this.json = json;
    }

    public Map<String, Object> tracks(String id) {
        var song = songs.get(id);
        var source = sources.get(song.sourceId());
        String relative = song.relativePath();
        if (relative.startsWith("/")
                || relative.contains("\\")
                || Arrays.stream(relative.split("/", -1))
                        .anyMatch(s -> s.isBlank() || s.equals("..") || s.equals(".")))
            throw TvService.fail(400, "媒体路径无效");
        int dot = relative.lastIndexOf('.');
        String stem = dot > relative.lastIndexOf('/') ? relative.substring(0, dot) : relative;
        var items = new ArrayList<Map<String, String>>();
        var warnings = new ArrayList<String>();
        for (String type : List.of("lrc", "srt", "vtt")) {
            try {
                byte[] bytes;
                if (source.type().equals("LOCAL")) {
                    Path root = Path.of(source.location()).toRealPath(),
                            file = root.resolve(stem + "." + type).toRealPath();
                    if (!file.startsWith(root) || !Files.isRegularFile(file))
                        throw new FileNotFoundException();
                    try (var in =
                            java.nio.channels.Channels.newInputStream(
                                    Files.newByteChannel(
                                            file,
                                            StandardOpenOption.READ,
                                            LinkOption.NOFOLLOW_LINKS))) {
                        bytes = bounded(in);
                    }
                } else {
                    URI base = URI.create(source.location());
                    String root =
                            base.getPath().endsWith("/") ? base.getPath() : base.getPath() + "/";
                    var target =
                            new URI(
                                    base.getScheme(),
                                    null,
                                    base.getHost(),
                                    base.getPort(),
                                    root + stem + "." + type,
                                    null,
                                    null);
                    var connection = (HttpURLConnection) target.toURL().openConnection();
                    try {
                        connection.setConnectTimeout(1500);
                        connection.setReadTimeout(2000);
                        connection.setInstanceFollowRedirects(false);
                        var row = sources.find(source.id());
                        String encrypted = (String) row.get("password_encrypted");
                        if (!source.username().isEmpty() || encrypted != null) {
                            String password =
                                    encrypted == null
                                            ? ""
                                            : crypto.decrypt(encrypted, "singverse:webdav:v1");
                            connection.setRequestProperty(
                                    "Authorization",
                                    "Basic "
                                            + Base64.getEncoder()
                                                    .encodeToString(
                                                            (source.username() + ":" + password)
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8)));
                        }
                        int status = connection.getResponseCode();
                        if (status == 404) continue;
                        if (status != 200) throw new IOException();
                        try (var in = connection.getInputStream()) {
                            bytes = bounded(in);
                        }
                    } finally {
                        connection.disconnect();
                    }
                }
                String text = decode(bytes);
                items.add(Map.of("id", type, "format", type, "text", text));
            } catch (NoSuchFileException | FileNotFoundException missing) {
                /* A missing optional sidecar is normal. */
            } catch (Exception failure) {
                warnings.add(type + " 文本无法读取或格式无效");
            }
        }
        appendMetadata(id, items, warnings);
        return Map.of("songId", id, "items", items, "warnings", warnings);
    }

    private void appendMetadata(String id, List<Map<String, String>> items, List<String> warnings) {
        // A usable original LRC is authoritative. An empty/header-only/broken file must not hide
        // valid embedded lyrics. Replace that unusable entry only when a fallback is available.
        var originalLrc = items.stream().filter(item -> item.get("format").equals("lrc"))
                .findFirst().orElse(null);
        if (originalLrc != null && hasTimedLyrics(originalLrc.get("text"))) return;
        try {
            JsonNode lyrics = null;
            var cached = embedded.resource(Map.of("songId", id));
            if (!cached.isEmpty()) {
                var row = cached.getFirst();
                if (row.get("archived_at") != null
                        || row.get("changed") instanceof Number changed && changed.longValue() != 0) {
                    warnings.add("媒体已变化，等待重新读取元数据歌词");
                    return;
                }
                if (row.get("result_json") != null)
                    lyrics = json.readTree(row.get("result_json").toString()).path("lyrics");
            }
            if (lyrics == null || !lyrics.isObject()) {
                var local = storedSongs.find(id);
                if (!local.isEmpty() && local.getFirst().get("embedded_tags_json") != null)
                    lyrics = json.readTree(local.getFirst().get("embedded_tags_json").toString()).path("lyrics");
            }
            if (lyrics == null || !lyrics.isObject()) return;
            String text = lyrics.path("text").asText("");
            String format = "text";
            if (lyrics.path("timing").asText().equals("milliseconds") && lyrics.path("lines").isArray()) {
                var lrc = new StringBuilder();
                for (var line : lyrics.path("lines")) {
                    if (!line.path("time").isIntegralNumber() || !line.path("time").canConvertToLong()
                            || line.path("time").longValue() < 0 || !line.path("text").isTextual())
                        throw new IOException("Invalid synchronized lyrics");
                    long time = line.path("time").longValue();
                    lrc.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]%s%n", time / 60000,
                            time / 1000 % 60, time % 1000, line.path("text").asText()));
                    if (lrc.length() > LIMIT) throw new IOException("Lyrics too large");
                }
                if (!lrc.isEmpty()) { text = lrc.toString(); format = "lrc"; }
            } else if (hasTimedLyrics(text)) {
                // ID3 USLT can contain actual LRC despite its 'none' timing flag. Preserve those
                // timestamps, but never turn MPEG frame counts or plain text into invented time.
                format = "lrc";
            }
            if (text.isBlank()) return;
            if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IOException("Lyrics too large");
            if (originalLrc != null) {
                items.remove(originalLrc);
                warnings.add("原始 LRC 没有有效时间与正文，已使用元数据歌词");
            }
            items.add(Map.of("id", "metadata-lyrics", "format", format, "text", text));
        } catch (Exception unavailable) {
            warnings.add("元数据歌词无法读取或超过限制");
        }
    }

    private static boolean hasTimedLyrics(String text) {
        for (String line : text.split("\\R")) {
            var matcher = LRC_STAMP.matcher(line);
            boolean validTime = false;
            int end = -1;
            while (matcher.find()) {
                validTime |= Integer.parseInt(matcher.group(2)) < 60;
                end = matcher.end();
            }
            if (validTime && !line.substring(end).replaceAll("<[^>]*>", "").isBlank()) return true;
        }
        return false;
    }

    private static byte[] bounded(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) throw new IOException("Text too large");
        return bytes;
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int start = 0;
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
            charset = StandardCharsets.UTF_16LE;
            start = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
            charset = StandardCharsets.UTF_16BE;
            start = 2;
        } else if (bytes.length >= 3
                && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb
                && bytes[2] == (byte) 0xbf) start = 3;
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.length - start))
                .toString();
    }
}
