package com.mynora.singverse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

/** Real Java/Python discovery and encrypted pairing; no LAN sweep or developer credentials. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class ComputeLanIntegrationIT {
    @TempDir static Path temp;
    static final int COMPUTE_PORT = freePort();

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("pairing.db").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
        registry.add("singverse.compute.base-url", () -> "http://127.0.0.1:" + COMPUTE_PORT);
        registry.add("singverse.compute.token-file", () -> "");
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("service-cache").toString());
        registry.add("singverse.classification.worker-enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(java.time.Duration.ofSeconds(2)).build();

    HttpResponse<String> request(String path, String method, Object body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(45)).header("Content-Type", "application/json");
        if (csrf) {
            var response = request("/api/v1/auth/csrf", "GET", null, false);
            builder.header("X-CSRF-Token", json.readTree(response.body()).path("token").asText());
        }
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String path, String method, Object body) throws Exception {
        var response = request(path, method, body, !method.equals("GET"));
        assertThat(response.statusCode()).withFailMessage("%s %s returned %s", method, path, response.statusCode()).isBetween(200, 299);
        return json.readTree(response.body());
    }

    Process startCompute() throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("compute.python")).toAbsolutePath().toString(),
                "-m", "singverse_compute", "--host", "127.0.0.1", "--port", Integer.toString(COMPUTE_PORT))
                .directory(Path.of("../singverse-compute").toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(temp.resolve("compute.log").toFile()));
        builder.environment().put("COMPUTE_AUTH_TOKEN_FILE", temp.resolve("compute-token").toString());
        builder.environment().put("COMPUTE_TRANSCODE_ROOT", temp.resolve("temporary-executions").toString());
        builder.environment().put("COMPUTE_AUDIO_MODEL_REPO", temp.resolve("models/htdemucs").toString());
        builder.environment().put("COMPUTE_LAN_PAIRING_ENABLED", "true");
        builder.environment().remove("COMPUTE_MEDIA_ROOTS");
        var process = builder.start();
        for (int attempt = 0; attempt < 100; attempt++) {
            assertThat(process.isAlive()).as("isolated compute process stays alive").isTrue();
            try {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + COMPUTE_PORT + "/health"))
                        .timeout(java.time.Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return process;
            } catch (java.io.IOException notReady) { }
            Thread.sleep(100);
        }
        stop(process);
        throw new AssertionError("isolated compute did not start");
    }

    @Test void discoversPairsWithoutBrowserCredentialsAndRecoversSameIdentityAfterNodeRestart() throws Exception {
        assertThat(request("/api/v1/compute-node", "GET", null, false).statusCode()).isEqualTo(401);
        ok("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"));
        String discoveryPath = "/api/v1/compute-node/discovery", pairPath = "/api/v1/compute-node/pair";
        var scope = Map.of("network", "127.0.0.1/32", "port", COMPUTE_PORT);
        assertThat(request(discoveryPath, "POST", scope, false).statusCode()).isEqualTo(403);
        assertThat(request(discoveryPath, "POST", Map.of("network", "8.8.8.8/32"), true).statusCode()).isEqualTo(400);
        long revision = ok("/api/v1/compute-node", "GET", null).path("revision").asLong();
        Process worker = startCompute();
        try {
            var found = ok(discoveryPath, "POST", scope).path("items");
            assertThat(found).hasSize(1);
            var node = found.get(0);
            assertThat(node.path("pairingAvailable").asBoolean()).isTrue();
            String nodeId = node.path("nodeId").asText();
            var saved = ok(pairPath, "POST", Map.of("discoveryId", node.path("id").asText(), "revision", revision));
            assertThat(saved.path("mode").asText()).isEqualTo("MANUAL");
            assertThat(saved.path("hasToken").asBoolean()).isTrue();
            assertThat(saved.has("token") || saved.has("publicKey") || saved.has("privateKey")).isFalse();
            assertThat(saved.toString().contains(Files.readString(temp.resolve("compute-token")).strip()))
                    .as("browser configuration never includes the internal credential").isFalse();
            assertThat(testConnection(saved).path("status").asText()).isEqualTo("AVAILABLE");
            var unauthorized = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + COMPUTE_PORT + "/internal/v1/status"))
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
            assertThat(request(pairPath, "POST", Map.of("discoveryId", node.path("id").asText(), "revision", revision), true).statusCode()).isEqualTo(409);
            assertThat(ok("/api/v1/compute-node", "GET", null)).isEqualTo(saved);

            stop(worker); worker = null;
            Files.writeString(temp.resolve("compute-token"), UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""));
            worker = startCompute();
            assertThat(testConnection(saved).path("status").asText()).isEqualTo("AUTH_FAILED");
            var rediscovered = ok(discoveryPath, "POST", scope).path("items").get(0);
            assertThat(rediscovered.path("nodeId").asText()).isEqualTo(nodeId);
            assertThat(rediscovered.path("pairingAvailable").asBoolean()).isTrue();
            var recovered = ok(pairPath, "POST", Map.of("discoveryId", rediscovered.path("id").asText(), "revision", saved.path("revision").asLong()));
            assertThat(testConnection(recovered).path("status").asText()).isEqualTo("AVAILABLE");
            assertThat(recovered.path("revision").asLong()).isEqualTo(saved.path("revision").asLong() + 1);
        } finally { if (worker != null) stop(worker); }
    }

    JsonNode testConnection(JsonNode configuration) throws Exception {
        return ok("/api/v1/compute-node/test", "POST", Map.of("mode", "MANUAL", "endpoint", configuration.path("endpoint").asText(),
                "token", "", "clearToken", false, "mappings", List.of(), "revision", configuration.path("revision").asLong()));
    }

    static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    static int freePort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
