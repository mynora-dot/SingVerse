package com.mynora.singverse.infrastructure.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputeNodeClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void absentNodeIsDisabledWithoutANetworkRequest() {
        assertThat(new ComputeNodeClient("", JSON).probe().status()).isEqualTo("disabled");
    }

    @Test void readsOnlyExplicitlyAdvertisedCapabilities() throws Exception {
        withNode(200, "{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[]}", 0, client -> {
            var probe = client.probe();
            assertThat(probe.status()).isEqualTo("available");
            assertThat(probe.capabilities()).isEmpty();
        });
    }

    @Test void failuresAndIncompatibleResponsesDoNotBecomeCapabilities() throws Exception {
        for (var body : new String[]{"not json", "", "null", "{}",
                "{\"service\":\"other\",\"apiVersion\":\"1\",\"capabilities\":[]}",
                "{\"service\":\"singverse-compute\",\"apiVersion\":\"2\",\"capabilities\":[]}",
                "{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[null]}"}) {
            withNode(200, body, 0, client -> assertThat(client.probe().status()).isEqualTo("unavailable"));
        }
        withNode(503, "{}", 0, client -> assertThat(client.probe().status()).isEqualTo("unavailable"));
        withNode(302, "{}", 0, client -> assertThat(client.probe().status()).isEqualTo("unavailable"));
    }

    @Test void timeoutAndOfflineNodeReturnUnavailable() throws Exception {
        withNode(200, "{}", 300, client -> assertThat(client.probe().status()).isEqualTo("unavailable"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        var url = "http://127.0.0.1:" + server.getAddress().getPort();
        server.stop(0);
        assertThat(new ComputeNodeClient(url, JSON).probe().status()).isEqualTo("unavailable");
    }

    @Test void rejectsInvalidNodeOriginsWithoutIncludingTheirValueInErrors() {
        for (var url : new String[]{"file:///tmp/test", "http://user:secret@localhost:8090", "http://localhost/path",
                "http://localhost?secret=value", "http://localhost#fragment", ":invalid", "http://localhost:99999"}) {
            assertThatThrownBy(() -> new ComputeNodeClient(url, JSON))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
    }

    private void withNode(int status, String body, int delayMs, java.util.function.Consumer<ComputeNodeClient> check)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/internal/v1/capabilities", exchange -> {
                try {
                    if (delayMs > 0) Thread.sleep(delayMs);
                    var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            try {
                check.accept(new ComputeNodeClient(url, JSON, Duration.ofMillis(delayMs > 0 ? 80 : 2000)));
            } finally {
                server.stop(0);
            }
        }
    }
}
