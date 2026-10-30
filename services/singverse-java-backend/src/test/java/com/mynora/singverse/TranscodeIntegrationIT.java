package com.mynora.singverse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.transcoding.TranscodeService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual Java -> authenticated Python -> FFmpeg -> authorized Java media delivery. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class TranscodeIntegrationIT {
    @TempDir static Path temp;
    private static final int COMPUTE_PORT = freePort();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("transcode.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
        p.add("singverse.compute.base-url", () -> "http://127.0.0.1:" + COMPUTE_PORT);
        p.add("singverse.compute.token-file", () -> temp.resolve("compute-token").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TranscodeService service;
    @Autowired org.springframework.jdbc.core.JdbcTemplate database;
    @Autowired com.mynora.singverse.auth.crypto.AuthCrypto crypto;
    final HttpClient client =
            HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .build();

    HttpResponse<String> req(String path, String method, Object body, boolean csrf)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(java.time.Duration.ofSeconds(60));
        if (csrf)
            b.header(
                    "X-CSRF-Token",
                    json.readTree(req("/api/v1/auth/csrf", "GET", null, false).body())
                            .path("token")
                            .asText());
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String path, String method, Object body, boolean csrf) throws Exception {
        var r = req(path, method, body, csrf);
        assertThat(r.statusCode()).withFailMessage(r.body()).isBetween(200, 299);
        return r.body().isEmpty() ? json.createObjectNode() : json.readTree(r.body());
    }

    @Test
    void realtimeOfflineSecurityAndNodeFailure() throws Exception {
        Path media = Files.createDirectories(temp.resolve("media"));
        Path source = media.resolve("歌手-测试歌曲.mkv");
        run(
                "ffmpeg",
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=s=160x90:r=25:d=2",
                "-f",
                "lavfi",
                "-i",
                "sine=duration=2",
                "-c:v",
                "mpeg4",
                "-c:a",
                "pcm_s16le",
                source.toString());
        byte[] original = Files.readAllBytes(source);
        Files.writeString(temp.resolve("compute-token"), "t".repeat(64));
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("compute.python")).toAbsolutePath().toString(),
                        "-m",
                        "singverse_compute",
                        "--port",
                        Integer.toString(COMPUTE_PORT));
        builder.directory(Path.of("../singverse-compute").toFile());
        builder.environment().put("COMPUTE_MEDIA_ROOTS", media.toString());
        builder.environment()
                .put("COMPUTE_AUTH_TOKEN_FILE", temp.resolve("compute-token").toString());
        builder.environment().put("COMPUTE_TRANSCODE_ROOT", temp.resolve("cache").toString());
        builder.environment().put("COMPUTE_HARDWARE_BACKEND", "software");
        builder.environment().put("COMPUTE_MINIMUM_FREE_BYTES", "0");
        builder.environment().put("COMPUTE_JAVA_ORIGIN", "http://127.0.0.1:" + port);
        builder.redirectErrorStream(true).redirectOutput(temp.resolve("compute.log").toFile());
        Process worker = builder.start();
        try {
            for (int i = 0; i < 100; i++) {
                try {
                    service.capabilities();
                    break;
                } catch (Exception e) {
                    Thread.sleep(100);
                }
            }
            assertThat(service.capabilities().path("available").asBoolean()).isTrue();
            assertThat(req("/api/v1/transcoding/offline", "GET", null, false).statusCode())
                    .isEqualTo(401);
            ok(
                    "/api/v1/auth/login",
                    "POST",
                    Map.of("username", "admin", "password", "admin"),
                    true);
            assertThat(
                            req(
                                            "/api/v1/transcoding/hardware-settings",
                                            "POST",
                                            Map.of("backend", "software", "revision", 0),
                                            false)
                                    .statusCode())
                    .isEqualTo(403);
            var hardware = ok("/api/v1/transcoding/hardware-settings", "GET", null, false);
            var sample =
                    ok(
                            "/api/v1/transcoding/hardware-settings/test",
                            "POST",
                            Map.of("backend", "software"),
                            true);
            assertThat(sample.path("passed").asBoolean()).isTrue();
            var savedHardware =
                    ok(
                            "/api/v1/transcoding/hardware-settings",
                            "POST",
                            Map.of(
                                    "backend",
                                    "software",
                                    "revision",
                                    hardware.path("revision").asLong()),
                            true);
            assertThat(savedHardware.path("effectiveBackend").asText()).isEqualTo("software");
            assertThat(
                            req(
                                            "/api/v1/transcoding/hardware-settings",
                                            "POST",
                                            Map.of(
                                                    "backend",
                                                    "auto",
                                                    "revision",
                                                    hardware.path("revision").asLong()),
                                            true)
                                    .statusCode())
                    .isEqualTo(409);
            var created =
                    ok(
                            "/api/v1/media-sources",
                            "POST",
                            Map.of("name", "转码测试", "type", "LOCAL", "location", media.toString()),
                            true);
            String sourceId = created.path("id").asText();
            ok("/api/v1/media-sources/" + sourceId + "/scan", "POST", null, true);
            String song =
                    ok("/api/v1/media-sources/" + sourceId + "/songs", "GET", null, false)
                            .path("items")
                            .get(0)
                            .path("id")
                            .asText();
            assertThat(
                            req(
                                            "/api/v1/transcoding/offline",
                                            "POST",
                                            Map.of("songIds", List.of(song)),
                                            false)
                                    .statusCode())
                    .isEqualTo(403);
            var resource =
                    ok(
                            "/api/v1/transcoding/playback/" + song,
                            "POST",
                            Map.of(
                                    "client",
                                    Map.of("video", List.of("h264"), "audio", List.of("aac"))),
                            true);
            String id = resource.path("id").asText();
            assertThat(id).isNotBlank();
            assertThat(service.require(id, "admin")).containsEntry("owner_id", "admin");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.require(id, "tv:another-device"))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            for (int i = 0; i < 100 && !resource.path("status").asText().equals("READY"); i++) {
                Thread.sleep(50);
                resource = ok("/api/v1/transcoding/sessions/" + id, "GET", null, false);
            }
            assertThat(resource.path("status").asText()).isEqualTo("READY");
            assertThat(req(resource.path("url").asText(), "GET", null, false).body())
                    .contains("#EXTM3U");
            assertThat(
                            req(
                                            "/api/v1/transcoding/sessions/"
                                                    + id
                                                    + "/artifacts/execution.json",
                                            "GET",
                                            null,
                                            false)
                                    .statusCode())
                    .isEqualTo(404);
            assertThat(
                            req("/api/v1/internal/transcode-input/" + id, "GET", null, false)
                                    .statusCode())
                    .isEqualTo(401);
            String offline =
                    ok(
                                    "/api/v1/transcoding/offline",
                                    "POST",
                                    Map.of("songIds", List.of(song)),
                                    true)
                            .get(0)
                            .asText();
            database.update("UPDATE media_transcode SET pipeline_version='1' WHERE id=?", offline);
            for (int i = 0; i < 100; i++) {
                service.tick();
                var row = service.require(offline, "admin");
                if (row.get("state").equals("SUCCEEDED")) break;
                Thread.sleep(100);
            }
            assertThat(service.require(offline, "admin"))
                    .containsEntry("state", "SUCCEEDED")
                    .containsEntry("pipeline_version", "2");
            assertThat(Files.readAllBytes(source)).isEqualTo(original);
            // Change only isolated fixture routing to exercise the real WebDAV -> Java -> loopback
            // FFmpeg input gateway.
            var dav =
                    com.sun.net.httpserver.HttpServer.create(
                            new InetSocketAddress("127.0.0.1", 0), 0);
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            dav.createContext(
                    "/dav/",
                    exchange -> {
                        reads.incrementAndGet();
                        String expected =
                                "Basic "
                                        + Base64.getEncoder()
                                                .encodeToString(
                                                        "reader:fixture-password"
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8));
                        if (!expected.equals(
                                exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.sendResponseHeaders(401, -1);
                            exchange.close();
                            return;
                        }
                        int from = 0, to = original.length - 1;
                        String range = exchange.getRequestHeaders().getFirst("Range");
                        if (range != null) {
                            var parts = range.substring(6).split("-", -1);
                            from = Integer.parseInt(parts[0]);
                            if (!parts[1].isBlank()) to = Math.min(to, Integer.parseInt(parts[1]));
                        }
                        if (from > to) {
                            exchange.getResponseHeaders()
                                    .set("Content-Range", "bytes */" + original.length);
                            exchange.sendResponseHeaders(416, -1);
                            exchange.close();
                            return;
                        }
                        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                        exchange.getResponseHeaders().set("Content-Type", "video/x-matroska");
                        if (range != null)
                            exchange.getResponseHeaders()
                                    .set(
                                            "Content-Range",
                                            "bytes " + from + "-" + to + "/" + original.length);
                        exchange.getResponseHeaders()
                                .set("Content-Length", Integer.toString(to - from + 1));
                        boolean head = exchange.getRequestMethod().equals("HEAD");
                        exchange.sendResponseHeaders(
                                range == null ? 200 : 206, head ? -1 : to - from + 1);
                        if (!head) exchange.getResponseBody().write(original, from, to - from + 1);
                        exchange.close();
                    });
            dav.start();
            try {
                database.update(
                        "UPDATE media_source SET"
                                + " source_type='WEBDAV',location=?,username=?,password_encrypted=?"
                                + " WHERE id=?",
                        "http://127.0.0.1:" + dav.getAddress().getPort() + "/dav/",
                        "reader",
                        crypto.encrypt("fixture-password", "singverse:webdav:v1"),
                        sourceId);
                var remote =
                        ok(
                                "/api/v1/transcoding/playback/" + song,
                                "POST",
                                Map.of("force", true),
                                true);
                for (int i = 0; i < 100 && !remote.path("status").asText().equals("READY"); i++) {
                    Thread.sleep(100);
                    remote =
                            ok(
                                    "/api/v1/transcoding/sessions/" + remote.path("id").asText(),
                                    "GET",
                                    null,
                                    false);
                }
                assertThat(remote.path("status").asText()).isEqualTo("READY");
                assertThat(reads.get()).isGreaterThan(0);
                assertThat(req(remote.path("url").asText(), "GET", null, false).body())
                        .contains("#EXTM3U");
            } finally {
                dav.stop(0);
                database.update(
                        "UPDATE media_source SET"
                            + " source_type='LOCAL',location=?,username='',password_encrypted=NULL"
                            + " WHERE id=?",
                        created.path("location").asText(),
                        sourceId);
            }
            worker.destroy();
            worker.waitFor(10, TimeUnit.SECONDS);
            var version =
                    ok(
                            "/api/v1/transcoding/playback/" + song,
                            "POST",
                            Map.of(
                                    "client",
                                    Map.of(
                                            "video",
                                            List.of("h264"),
                                            "audio",
                                            List.of("aac"),
                                            "containers",
                                            List.of("mp4"))),
                            true);
            assertThat(version.path("mode").asText()).isEqualTo("VERSION");
            var bytes =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + version.path("url").asText()))
                                    .header("Range", "bytes=0-15")
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertThat(bytes.statusCode()).isEqualTo(206);
            assertThat(bytes.body()).hasSize(16);
            assertThat(req("/api/health", "GET", null, false).statusCode()).isEqualTo(200);
        } finally {
            worker.destroy();
            if (!worker.waitFor(10, TimeUnit.SECONDS)) worker.destroyForcibly();
        }
    }

    static void run(String... args) throws Exception {
        var p =
                new ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
    }

    static int freePort() {
        try (var s = new java.net.ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
