package com.mynora.singverse;

import com.mynora.singverse.compute.ComputeNodeProbe;
import com.mynora.singverse.compute.ComputeNodeSettings;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 项目内部两种运行时的组装验证，不为计算节点增加客户端菜单或管理API。 */
@ActiveProfiles("sqlite")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ComputeIntegrationIT {
    @TempDir static Path temp;
    private static final int COMPUTE_PORT = freePort();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("backend-test.db").toString());
        registry.add("singverse.compute.base-url", () -> "http://127.0.0.1:" + COMPUTE_PORT);
        registry.add("singverse.compute.token-file", () -> temp.resolve("compute-token").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        registry.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        registry.add("singverse.classification.worker-enabled", () -> "false");
    }

    @Autowired ComputeNodeProbe compute;
    @Autowired ComputeNodeSettings settings;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .cookieHandler(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build();
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    static final String TOKEN = "isolated-compute-lifecycle-token-00000000";
    @Autowired TestRestTemplate http;

    @Test void computeLifecycleIsIntegratedWithoutBlockingTheBusinessService() throws Exception {
        Files.writeString(temp.resolve("compute-token"), TOKEN);
        // Establish the real authenticated callback origin before testing the node.
        var csrf = request("/api/v1/auth/csrf", "GET", null, null);
        request("/api/v1/auth/login", "POST", "{\"username\":\"admin\",\"password\":\"admin\"}", json.readTree(csrf.body()).path("token").asText());
        request("/api/v1/auth/session", "GET", null, null);
        // Java此时已在计算服务未运行的条件下启动。
        assertThat(compute.check(settings.current()).status()).isEqualTo("UNAVAILABLE");
        assertBusinessServiceAvailable();

        var python = Path.of(System.getProperty("compute.python")).toAbsolutePath();
        assertThat(Files.isExecutable(python)).as("Run make compute-setup with Python 3.12+ first").isTrue();
        var log = temp.resolve("compute.log");
        var builder = new ProcessBuilder(python.toString(), "-m", "singverse_compute",
                "--host", "127.0.0.1", "--port", Integer.toString(COMPUTE_PORT))
                .directory(Path.of("../singverse-compute").toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("COMPUTE_AUTH_TOKEN_FILE", temp.resolve("compute-token").toString());
        builder.environment().put("COMPUTE_TRANSCODE_ROOT", temp.resolve("executions").toString());
        builder.environment().put("COMPUTE_AUDIO_MODEL_REPO", temp.resolve("models/htdemucs").toString());
        var worker = builder.start();
        try {
            var probe = compute.check(settings.current());
            for (int attempt = 0; attempt < 80 && !"AVAILABLE".equals(probe.status()); attempt++) {
                assertThat(worker.isAlive()).as("Python compute process must remain running").isTrue();
                Thread.sleep(100);
                probe = compute.check(settings.current());
            }
            assertThat(probe.status()).isEqualTo("AVAILABLE");
            assertThat(probe.capabilities()).doesNotContain("media-tags");
            assertBusinessServiceAvailable();
        } finally {
            worker.destroy();
            if (!worker.waitFor(10, TimeUnit.SECONDS)) {
                worker.destroyForcibly();
                assertThat(worker.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        // 真实Python进程停止后，业务服务仍可使用；不会静默启用占位能力。
        assertThat(compute.check(settings.current()).status()).isEqualTo("UNAVAILABLE");
        assertThat(compute.check(settings.current()).capabilities()).isEmpty();
        assertBusinessServiceAvailable();
    }

    private java.net.http.HttpResponse<String> request(String path, String method, String body, String csrf) throws Exception {
        var builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        var result = client.send(builder.method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200); return result;
    }

    private void assertBusinessServiceAvailable() {
        var health = http.getForEntity("/api/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).isEqualTo("{\"status\":\"UP\"}");
        var home = http.getForEntity("/party/", String.class);
        assertThat(home.getStatusCode().value()).isEqualTo(200);
        assertThat(home.getBody()).contains("SingVerse", "id=\"app\"", "type=\"module\"");
        var script = java.util.regex.Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(home.getBody());
        assertThat(script.find()).as("Party entry must reference its built module").isTrue();
        var asset = java.net.URI.create("/party/").resolve(script.group(1)).toString();
        assertThat(http.getForEntity(asset, String.class).getStatusCode().value()).isEqualTo(200);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
