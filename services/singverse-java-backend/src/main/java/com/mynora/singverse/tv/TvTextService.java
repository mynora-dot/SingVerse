package com.mynora.singverse.tv;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.*;

import org.springframework.stereotype.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads only same-stem text sidecars inside an already authorized media source. Never writes media.
 */
@Service
public class TvTextService {
    private static final int LIMIT = 131072;
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final AuthCrypto crypto;

    public TvTextService(
            MediaSongRepository songs, MediaSourceRepository sources, AuthCrypto crypto) {
        this.songs = songs;
        this.sources = sources;
        this.crypto = crypto;
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
        return Map.of("songId", id, "items", items, "warnings", warnings);
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
