package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

final class MusicSourceHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    private final MusicProvider provider;
    private final Set<String> hosts;
    MusicSourceHttp(ObjectMapper mapper, MusicProvider provider, Set<String> hosts) { this.mapper = mapper; this.provider = provider; this.hosts = hosts; }
    JsonNode get(String url, Map<String, ?> headers, Duration timeout) { return send(HttpRequest.newBuilder(checked(url)).GET(), headers, timeout); }
    JsonNode form(String url, Map<String, String> body, Map<String, ?> headers, Duration timeout) {
        return send(HttpRequest.newBuilder(checked(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(query(body))), headers, timeout);
    }
    private JsonNode send(HttpRequest.Builder builder, Map<String, ?> headers, Duration timeout) {
        headers.forEach((key, value) -> builder.header(key, String.valueOf(value)));
        if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("User-Agent"))) builder.header("User-Agent", "SingVerse/0.1 metadata-only");
        try {
            var response = client.send(builder.timeout(timeout).header("Accept", "application/json")
                    .build(),
                    info -> new BoundedResponse(2_000_000));
            if (response.statusCode() != 200) throw new MusicSourceException(provider, "平台返回 HTTP " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (MusicSourceException error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "平台连接失败或响应无效，请稍后重试");
        }
    }
    private URI checked(String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !hosts.contains(uri.getHost()) || uri.getRawUserInfo() != null || uri.getPort() != -1)
            throw new MusicSourceException(provider, "平台请求地址无效");
        return uri;
    }
    static String query(Map<String, ?> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }
}
