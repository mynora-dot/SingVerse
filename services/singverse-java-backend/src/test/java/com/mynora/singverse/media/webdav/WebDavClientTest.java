package com.mynora.singverse.media.webdav;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class WebDavClientTest {
    HttpServer server;
    ExecutorService threads;
    @AfterEach void stop() { if (server != null) server.stop(0); if (threads != null) threads.shutdownNow(); }
    @Test void pathDecodesOnceAndRejectsTraversalCrossOriginAndEncodedSeparators() {
        URI root = URI.create("https://nas.example/dav/%E6%AD%8C%E6%9B%B2/");
        String directory = root.getPath();
        assertThat(WebDavClient.path(root, directory, "100%25%20+%252F.mkv", false)).isEqualTo(directory + "100% +%2F.mkv");
        assertThat(WebDavClient.path(root, directory, "https://nas.example:443/dav/歌曲/晴 天.mkv", false)).isEqualTo(directory + "晴 天.mkv");
        for (String href : List.of("../escape/", "%2e%2e/escape/", "https://other.example/dav/歌曲/a.mkv", "//other.example/dav/a.mkv",
                "a%2fb.mkv", "a%5Cb.mkv", "a.mkv?token=x", "/dav/歌曲2/a.mkv", "a%00.mkv", "nested/a.mkv"))
            assertThatThrownBy(() -> WebDavClient.path(root, directory, href, false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void unicodeDirectoriesArePercentEncodedOnTheWireWithoutDoubleEncoding() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool(); server.setExecutor(threads);
        String directory = "/家庭 曲库/100%+/";
        String encoded = new URI(null, null, directory, null).toASCIIString();
        List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            // Return the canonical encoded href regardless of how the request was encoded.
            byte[] response = ("<d:multistatus xmlns:d='DAV:'><d:response><d:href>" + encoded
                    + "</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>"
                    + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                    + "<d:response><d:href>" + encoded + "%E6%AD%8C%E6%9B%B2%20%252F.mkv</d:href>"
                    + "<d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status>"
                    + "</d:propstat></d:response></d:multistatus>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(207, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + encoded);
        List<WebDavClient.Entry> found = new ArrayList<>();
        new WebDavClient().list(root, directory, null, System.nanoTime() + Duration.ofSeconds(10).toNanos(), () -> {}, stream -> {}, found::add);
        assertThat(requested).containsExactly("PROPFIND " + encoded);
        assertThat(requested.getFirst().chars().allMatch(c -> c < 128)).isTrue();
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().path()).isEqualTo(directory + "歌曲 %2F.mkv");
    }
    @Test void boundsHeaderAndSlowStreamingBodyAndResponseSize() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool(); server.setExecutor(threads);
        server.createContext("/", e -> {
            try {
                assertThat(e.getRequestMethod()).isEqualTo("PROPFIND");
                assertThat(e.getRequestHeaders().getFirst("Authorization")).isNull();
                String mode = e.getRequestURI().getPath();
                if (mode.equals("/headers/")) Thread.sleep(1500);
                e.sendResponseHeaders(207, 0);
                e.getResponseBody().write("<d:multistatus xmlns:d='DAV:'>".getBytes()); e.getResponseBody().flush();
                if (mode.equals("/body/")) Thread.sleep(1500);
                e.getResponseBody().write((" ".repeat(2000) + "</d:multistatus>").getBytes());
            } catch (Exception ignored) { } finally { e.close(); }
        }); server.start();
        WebDavClient client = new WebDavClient(Duration.ofMillis(200), 1024);
        for (String path : List.of("/headers/", "/body/", "/size/")) {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.list(root, path, null, System.nanoTime() + Duration.ofSeconds(10).toNanos(), () -> {}, stream -> {}, entry -> {}))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        }
    }
}
