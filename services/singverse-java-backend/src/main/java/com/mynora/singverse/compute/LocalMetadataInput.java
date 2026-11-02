package com.mynora.singverse.compute;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived bounded grants for scanning files before a song row exists. */
@Component
public class LocalMetadataInput {
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private static final class Grant {
        final Path file;
        final BasicFileAttributes attrs;
        final String token;
        final long expires = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        int requests;
        long bytes;
        Grant(Path file, String token) throws IOException {
            this.file = file.toRealPath(); this.token = token;
            attrs = Files.readAttributes(this.file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new IOException("Not a regular file");
        }
    }
    public String authorize(Path file, String token) throws IOException {
        grants.entrySet().removeIf(e -> e.getValue().expires < System.nanoTime());
        String id = UUID.randomUUID().toString(); grants.put(id, new Grant(file, token)); return id;
    }
    public void revoke(String id) { grants.remove(id); }
    public boolean contains(String id) { return grants.containsKey(id); }
    private static ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
    public void read(String id, String authorization, String range, boolean head, HttpServletResponse response) throws IOException {
        var grant = grants.get(id);
        if (grant == null || authorization == null || !MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + grant.token).getBytes(StandardCharsets.UTF_8))) throw fail(401, "媒体读取授权无效");
        synchronized (grant) {
            if (grants.get(id) != grant || System.nanoTime() >= grant.expires) throw fail(410, "媒体读取授权已结束");
            var attrs = Files.readAttributes(grant.file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() != grant.attrs.size() || !attrs.lastModifiedTime().equals(grant.attrs.lastModifiedTime())
                    || !Objects.equals(attrs.fileKey(), grant.attrs.fileKey()) || !grant.file.toRealPath().equals(grant.file)) throw fail(409, "源媒体已变化");
            if (range == null || !range.matches("bytes=[0-9]+-[0-9]+")) throw fail(400, "必须指定有界单段字节范围");
            long start, end;
            try {
                String[] values = range.substring(6).split("-");
                start = Long.parseLong(values[0]); end = Long.parseLong(values[1]);
                if (end < start || end - start >= 1024 * 1024) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw fail(400, "字节范围超限"); }
            if (++grant.requests > 16 || grant.bytes + end - start + 1 > 16 * 1024 * 1024) throw fail(413, "媒体读取预算已用尽");
            if (start >= attrs.size()) { response.setStatus(416); response.setHeader("Content-Range", "bytes */" + attrs.size()); return; }
            end = Math.min(end, attrs.size() - 1);
            int length = (int)(end - start + 1);
            response.setStatus(206); response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + attrs.size());
            response.setHeader("Accept-Ranges", "bytes"); response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/octet-stream"); response.setContentLength(length);
            if (!head) try (var channel = Files.newByteChannel(grant.file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.position(start);
                var buffer = java.nio.ByteBuffer.allocate(length);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
                if (buffer.hasRemaining()) throw fail(409, "源媒体已变化");
                grant.bytes += length; response.getOutputStream().write(buffer.array());
            }
        }
    }
}
