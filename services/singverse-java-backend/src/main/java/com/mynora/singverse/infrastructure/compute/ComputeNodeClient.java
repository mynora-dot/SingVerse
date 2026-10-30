package com.mynora.singverse.infrastructure.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 内部能力探测入口；不参与主服务健康检查，也不在启动时要求计算节点在线。 */
@Component
public class ComputeNodeClient {
    private final java.util.function.Supplier<URI> endpoints;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;

    @Autowired
    public ComputeNodeClient(com.mynora.singverse.compute.ComputeNodeSettings settings, ObjectMapper json) {
        this.endpoints=()->{var connection=settings.current();return connection==null?null:connection.origin().resolve("/internal/v1/capabilities");};
        this.json=json; this.timeout=Duration.ofSeconds(2);
        this.http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ComputeNodeClient(@Value("${singverse.compute.base-url:}") String baseUrl, ObjectMapper json) {
        this(baseUrl, json, Duration.ofSeconds(2));
    }

    ComputeNodeClient(String baseUrl, ObjectMapper json, Duration timeout) {
        var fixed=endpoint(baseUrl); this.endpoints=()->fixed;
        this.json = json;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Probe probe() {
        URI endpoint=endpoints.get();
        if (endpoint == null) return new Probe("disabled", List.of());
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Accept", "application/json").GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return unavailable();
            var body = json.readTree(response.body());
            if (body == null || !body.isObject()) return unavailable();
            var capabilities = body.path("capabilities");
            if (!"singverse-compute".equals(body.path("service").asText())
                    || !"1".equals(body.path("apiVersion").asText()) || !capabilities.isArray()) {
                return unavailable();
            }
            var values = new java.util.ArrayList<String>();
            for (var capability : capabilities) {
                if (!capability.isTextual() || capability.asText().isBlank()) return unavailable();
                values.add(capability.asText());
            }
            return new Probe("available", List.copyOf(values));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return unavailable();
        } catch (java.io.IOException error) {
            // 不记录节点响应、内部地址或未来可能携带的凭据。
            return unavailable();
        }
    }

    private static Probe unavailable() {
        return new Probe("unavailable", List.of());
    }

    private static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        try {
            var uri = URI.create(baseUrl.strip());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            return uri.resolve("/internal/v1/capabilities");
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("COMPUTE_NODE_URL must be an HTTP(S) origin without credentials, path or query");
        }
    }

    public record Probe(String status, List<String> capabilities) {}
}
