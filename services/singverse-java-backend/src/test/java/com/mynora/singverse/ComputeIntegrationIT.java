package com.mynora.singverse;

import com.mynora.singverse.infrastructure.compute.ComputeNodeClient;

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
class ComputeIntegrationIT {
    @TempDir static Path temp;
    private static final int COMPUTE_PORT = freePort();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("backend-test.db").toString());
        registry.add("singverse.compute.base-url", () -> "http://127.0.0.1:" + COMPUTE_PORT);
    }

    @Autowired ComputeNodeClient compute;
    @Autowired TestRestTemplate http;

    @Test void computeLifecycleIsIntegratedWithoutBlockingTheBusinessService() throws Exception {
        // Java此时已在计算服务未运行的条件下启动。
        assertThat(compute.probe().status()).isEqualTo("unavailable");
        assertBusinessServiceAvailable();

        var python = Path.of(System.getProperty("compute.python")).toAbsolutePath();
        assertThat(Files.isExecutable(python)).as("Run make compute-setup with Python 3.12+ first").isTrue();
        var log = temp.resolve("compute.log");
        var worker = new ProcessBuilder(python.toString(), "-m", "singverse_compute",
                "--host", "127.0.0.1", "--port", Integer.toString(COMPUTE_PORT))
                .directory(Path.of("../singverse-compute").toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            ComputeNodeClient.Probe probe = compute.probe();
            for (int attempt = 0; attempt < 80 && !"available".equals(probe.status()); attempt++) {
                assertThat(worker.isAlive()).as("Python compute process must remain running").isTrue();
                Thread.sleep(100);
                probe = compute.probe();
            }
            assertThat(probe.status()).isEqualTo("available");
            assertThat(probe.capabilities()).isEmpty();
            assertBusinessServiceAvailable();
        } finally {
            worker.destroy();
            if (!worker.waitFor(10, TimeUnit.SECONDS)) {
                worker.destroyForcibly();
                assertThat(worker.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        // 真实Python进程停止后，业务服务仍可使用；不会静默启用占位能力。
        assertThat(compute.probe().status()).isEqualTo("unavailable");
        assertThat(compute.probe().capabilities()).isEmpty();
        assertBusinessServiceAvailable();
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
