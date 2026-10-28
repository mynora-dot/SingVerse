package com.mynora.singverse.media.webdav;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.xml.stream.*;
import org.springframework.stereotype.Component;

/** Only enumerates collections. Never requests media contents, content types or download links. */
@Component
public class WebDavClient {
    public record Entry(String path, boolean directory, Long size, String modifiedAt) {}
    private static final String BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>
        """;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webdav-response-timeout"); t.setDaemon(true); return t;
    });
    private final Duration requestTimeout;
    private final long maxBytes;
    public WebDavClient() { this(Duration.ofSeconds(60), 64L * 1024 * 1024); }
    WebDavClient(Duration requestTimeout, long maxBytes) { this.requestTimeout = requestTimeout; this.maxBytes = maxBytes; }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public void list(URI root, String directory, String authorization, long deadline,
            Runnable check, Consumer<AutoCloseable> track, Consumer<Entry> consumer) {
        check.run();
        long remaining = Math.min(requestTimeout.toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) throw failure("WebDAV 扫描超过两小时，请拆分来源后重试");
        long requestDeadline = System.nanoTime() + remaining;
        try {
            // Send an ASCII request target. Some WebDAV servers interpret raw Unicode path bytes
            // differently, returning a different href even though they respond with 207.
            URI target = URI.create(new URI(root.getScheme(), null, root.getHost(), root.getPort(), directory, null, null).toASCIIString());
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofNanos(remaining))
                    .header("Depth", "1").header("Content-Type", "application/xml; charset=utf-8")
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(BODY));
            if (authorization != null) request.header("Authorization", authorization);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                track.accept(input);
                var alarm = TIMEOUTS.schedule(() -> close(input), Math.max(0, requestDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                try {
                    check.run();
                    if (response.statusCode() != 207) throw failure(switch (response.statusCode()) {
                        case 401 -> "WebDAV 认证失败，请检查用户名和密码";
                        case 403 -> "WebDAV 无目录读取权限，请检查 OpenList 的 WebDAV 读取权限";
                        case 404 -> "WebDAV 目录不存在，请检查 /dav/ 地址及挂载目录";
                        case 301, 302, 303, 307, 308 -> "WebDAV 地址发生重定向，请直接配置最终目录地址";
                        default -> "WebDAV 目录请求失败（HTTP " + response.statusCode() + "），旧索引已保留";
                    });
                    parse(root, directory, new BoundedInput(input, maxBytes), () -> {
                        check.run();
                        if (System.nanoTime() > requestDeadline) throw failure("WebDAV 单目录请求超过 60 秒");
                    }, consumer);
                } finally { alarm.cancel(false); track.accept(null); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw failure("WebDAV 扫描已中断");
        } catch (IOException | java.net.URISyntaxException | XMLStreamException e) {
            // Never expose response XML, remote URLs, credentials or low-level exception messages.
            throw failure("WebDAV 目录读取失败、超时或响应无效，旧索引已保留");
        }
    }

    private static void parse(URI root, String directory, InputStream input, Runnable check,
            Consumer<Entry> consumer) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External entity forbidden"); });
        XMLStreamReader reader = factory.createXMLStreamReader(input, "UTF-8");
        boolean multistatus = false, self = false;
        try {
            while (reader.hasNext()) {
                check.run();
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                    throw failure("WebDAV XML 不允许 DTD 或外部实体");
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                if (!multistatus) {
                    if (!dav(reader, "multistatus")) throw failure("WebDAV 返回的不是目录列表");
                    multistatus = true;
                } else if (dav(reader, "response")) {
                    Entry entry = response(root, directory, reader, check);
                    if (entry.path().equals(directory)) {
                        if (!entry.directory()) throw failure("WebDAV 地址不是目录");
                        self = true;
                    } else consumer.accept(entry);
                }
            }
            if (!multistatus || !self) throw failure("WebDAV 目录响应缺少当前目录，旧索引已保留");
        } finally { reader.close(); }
    }

    private static Entry response(URI root, String directory, XMLStreamReader reader, Runnable check) throws XMLStreamException {
        String href = null;
        boolean typeKnown = false, collection = false;
        Long size = null; String modified = null;
        while (reader.hasNext()) {
            check.run();
            int event = reader.next();
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw failure("WebDAV XML 包含禁止内容");
            if (event == XMLStreamConstants.END_ELEMENT && dav(reader, "response")) break;
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            if (dav(reader, "href")) {
                if (href != null) throw failure("WebDAV 条目有重复路径");
                href = reader.getElementText();
            } else if (dav(reader, "status")) {
                if (!success(reader.getElementText())) throw failure("WebDAV 条目读取失败，旧索引已保留");
            } else if (dav(reader, "propstat")) {
                String status = null; boolean known = false, folder = false;
                Long length = null; String date = null;
                while (reader.hasNext()) {
                    check.run();
                    event = reader.next();
                    if (event == XMLStreamConstants.END_ELEMENT && dav(reader, "propstat")) break;
                    if (event != XMLStreamConstants.START_ELEMENT) continue;
                    if (dav(reader, "status")) status = reader.getElementText();
                    else if (dav(reader, "resourcetype")) {
                        known = true;
                        while (reader.hasNext()) {
                            check.run(); event = reader.next();
                            if (event == XMLStreamConstants.END_ELEMENT && dav(reader, "resourcetype")) break;
                            if (event == XMLStreamConstants.START_ELEMENT && dav(reader, "collection")) folder = true;
                        }
                    } else if (dav(reader, "getcontentlength")) {
                        String text = reader.getElementText().trim();
                        try { length = Long.valueOf(text); if (length < 0) length = null; }
                        catch (NumberFormatException ignored) { length = null; }
                    } else if (dav(reader, "getlastmodified")) {
                        String text = reader.getElementText().trim();
                        try { date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString(); }
                        catch (java.time.format.DateTimeParseException ignored) { date = null; }
                    }
                }
                if (success(status)) {
                    if (known) { typeKnown = true; collection = folder; }
                    if (length != null) size = length;
                    if (date != null) modified = date;
                }
            }
        }
        if (href == null || !typeKnown) throw failure("WebDAV 条目缺少路径或目录类型，旧索引已保留");
        return new Entry(path(root, directory, href, collection), collection, size, modified);
    }

    static String path(URI root, String directory, String href, boolean collection) {
        try {
            if (href.length() > 32768 || href.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            URI current = new URI(root.getScheme(), null, root.getHost(), root.getPort(), directory, null, null);
            URI reference = URI.create(href.replace(" ", "%20"));
            String raw = reference.getRawPath();
            if (raw == null || raw.toLowerCase(Locale.ROOT).matches(".*%(2f|5c).*")) throw new IllegalArgumentException();
            // Reject traversal before URI.resolve can normalize away dot segments.
            for (String part : reference.getPath().split("/", -1))
                if (part.equals(".") || part.equals("..") || part.contains("\\")) throw new IllegalArgumentException();
            URI resolved = current.resolve(reference);
            if (!Objects.equals(root.getScheme(), resolved.getScheme())
                    || !root.getHost().equalsIgnoreCase(resolved.getHost()) || port(root) != port(resolved)
                    || resolved.getRawUserInfo() != null || resolved.getRawQuery() != null || resolved.getRawFragment() != null)
                throw new IllegalArgumentException();
            String path = resolved.getPath();
            if (path.chars().anyMatch(Character::isISOControl) || path.contains("//")) throw new IllegalArgumentException();
            if (collection && !path.endsWith("/")) path += "/";
            if (path.equals(directory)) return path;
            if (!path.startsWith(root.getPath()) || !path.startsWith(directory)) throw new IllegalArgumentException();
            String child = path.substring(directory.length());
            if (child.endsWith("/")) child = child.substring(0, child.length() - 1);
            if (child.isEmpty() || child.contains("/") || path.length() - root.getPath().length() > 2048)
                throw new IllegalArgumentException();
            return path;
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            throw failure("WebDAV 返回跨来源、越界或无效路径，旧索引已保留");
        }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
    private static boolean dav(XMLStreamReader r, String name) { return "DAV:".equals(r.getNamespaceURI()) && name.equals(r.getLocalName()); }
    private static boolean success(String status) { return status != null && status.trim().matches("HTTP/\\d(?:\\.\\d)? 2\\d\\d(?: .*)?"); }
    static IllegalStateException failure(String message) { return new IllegalStateException(message); }
    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) { } }
    private static final class BoundedInput extends FilterInputStream {
        private long remaining;
        BoundedInput(InputStream input, long limit) { super(input); remaining = limit; }
        private void used(int count) throws IOException { if (count > 0 && (remaining -= count) < 0) throw new IOException("Response limit"); }
        @Override public int read() throws IOException { int b = in.read(); used(b < 0 ? 0 : 1); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = in.read(b, off, (int) Math.min(len, remaining + 1)); used(n); return n; }
    }
}
