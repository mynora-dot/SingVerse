package com.mynora.singverse.media.service;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated, on-demand original-file delivery. No transcoding or persistent media cache. */
@Service
public class MediaPlaybackService {
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final AuthCrypto crypto;
    public MediaPlaybackService(MediaSongRepository songs, MediaSourceRepository sources, AuthCrypto crypto) {
        this.songs = songs; this.sources = sources; this.crypto = crypto;
    }

    public void play(String id, String range, boolean head, HttpServletResponse response) throws IOException {
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
        try {
            if (source.type().equals("LOCAL")) local(source.location(), relative, range, head, response);
            else remote(source, relative, range, head, response);
        } catch (ResponseStatusException error) {
            if (!response.isCommitted()) { response.reset(); response.setHeader("Cache-Control", "no-store"); }
            throw error;
        } catch (IOException error) {
            if (response.isCommitted()) throw error;
            response.reset(); response.setHeader("Cache-Control", "no-store");
            throw failure(source.type().equals("LOCAL") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY, "媒体文件无法读取或连接已中断");
        }
    }

    private void local(String location, String relative, String range, boolean head, HttpServletResponse response) throws IOException {
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
            if (!head) { channel.position(start); copy(Channels.newInputStream(channel), response.getOutputStream(), length); }
        } catch (AccessDeniedException error) { throw failure(HttpStatus.NOT_FOUND, "媒体文件无法读取"); }
    }

    private void remote(com.mynora.singverse.media.dto.MediaSourceDtos.Source source, String relative,
            String range, boolean head, HttpServletResponse response) throws IOException {
        HttpURLConnection connection = null;
        try {
            URI root = URI.create(source.location());
            String rootPath = root.getPath().endsWith("/") ? root.getPath() : root.getPath() + "/";
            URI target = new URI(root.getScheme(), null, root.getHost(), root.getPort(), rootPath + relative, null, null);
            connection = (HttpURLConnection) URI.create(target.toASCIIString()).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
            connection.setRequestMethod(head ? "HEAD" : "GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) connection.setRequestProperty("Range", range);
            var stored = sources.find(source.id());
            String encrypted = (String) stored.get("password_encrypted");
            if (!source.username().isEmpty() || encrypted != null) {
                String password = encrypted == null ? "" : crypto.decrypt(encrypted, "singverse:webdav:v1");
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (source.username() + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            int status = connection.getResponseCode();
            if (status == 416) {
                response.setStatus(416);
                String value = connection.getHeaderField("Content-Range");
                if (value != null && value.matches("bytes \\*/[0-9]+")) response.setHeader("Content-Range", value);
                return;
            }
            if (status != 200 && status != 206) throw failure(HttpStatus.BAD_GATEWAY,
                    status >= 300 && status < 400 ? "WebDAV 媒体请求发生重定向，请使用支持直接读取的 WebDAV 地址" : "WebDAV 媒体读取失败，请检查地址、凭据和文件权限");
            String contentRange = connection.getHeaderField("Content-Range");
            if (status == 206 && (range == null || contentRange == null || !contentRange.matches("bytes [0-9]+-[0-9]+/[0-9]+")))
                throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 返回无效的媒体范围");
            long length = connection.getContentLengthLong();
            response.setStatus(status);
            if (status == 206) response.setHeader("Content-Range", contentRange);
            if ("bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges")) || status == 206) response.setHeader("Accept-Ranges", "bytes");
            if (length >= 0) response.setContentLengthLong(length);
            if (!head) try (InputStream input = connection.getInputStream()) { copy(input, response.getOutputStream(), length); }
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw failure(HttpStatus.BAD_GATEWAY, "WebDAV 媒体地址无效");
        } catch (IOException error) {
            if (response.isCommitted()) throw error;
            throw failure(HttpStatus.BAD_GATEWAY, "媒体读取失败或超时，请稍后重试");
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static void copy(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (remaining != 0) {
            int count = input.read(buffer, 0, remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining));
            if (count < 0) { if (remaining > 0) throw new EOFException("Incomplete media response"); break; }
            output.write(buffer, 0, count); if (remaining > 0) remaining -= count;
        }
    }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
