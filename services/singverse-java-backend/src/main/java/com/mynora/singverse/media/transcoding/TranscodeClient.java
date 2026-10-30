package com.mynora.singverse.media.transcoding;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.compute.*;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.metadata.BoundedResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.net.http.*;
import java.time.Duration;

@Component
public class TranscodeClient {
    private final ComputeNodeSettings settings;
    private final ObjectMapper json;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public TranscodeClient(ComputeNodeSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public Connection connection() {
        var c = settings.current();
        if (c == null) throw fail(503, "计算节点未配置或已停用");
        return c;
    }

    public Connection connection(String origin) {
        var c = connection();
        if (!c.origin().toString().equals(origin)) throw fail(409, "计算节点配置已变化，请重建任务");
        return c;
    }

    private HttpRequest request(Connection c, String path, String method, JsonNode body) {
        try {
            ComputeNodeSettings.checkNetwork(c.origin());
        } catch (java.net.UnknownHostException e) {
            throw fail(503, "计算节点地址无法解析");
        }
        if (!path.matches("/internal/v1/transcoding/[a-zA-Z0-9/_.-]+")) throw fail(400, "无效的计算资源");
        return HttpRequest.newBuilder(c.origin().resolve(path))
                .timeout(Duration.ofSeconds(path.endsWith("/capabilities") ? 60 : 30))
                .header("Authorization", "Bearer " + c.token())
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    public JsonNode call(Connection c, String path, String method, JsonNode body) {
        try {
            var r = http.send(request(c, path, method, body), info -> new BoundedResponse(1048576));
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                int status =
                        switch (r.statusCode()) {
                            case 404, 409, 422, 429 -> r.statusCode();
                            default -> 502;
                        };
                String message =
                        status == 409
                                ? "配置或执行状态已变化，请重新读取"
                                : status == 422
                                        ? "所选加速方式或媒体任务测试未通过，请检查驱动和设备权限"
                                        : "计算任务请求失败（" + r.statusCode() + "）";
                throw fail(status, message);
            }
            return r.body().length == 0 ? json.createObjectNode() : json.readTree(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(503, "计算请求已中断");
        } catch (IOException e) {
            throw fail(503, "计算节点暂时无法连接");
        }
    }

    public HttpResponse<InputStream> artifact(Connection c, String path, String range) {
        try {
            var builder =
                    HttpRequest.newBuilder(request(c, path, "GET", null).uri())
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + c.token());
            if (range != null) {
                if (!range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw fail(400, "无效的字节范围");
                builder.header("Range", range);
            }
            var r = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200 && r.statusCode() != 206 && r.statusCode() != 416) {
                r.body().close();
                throw fail(502, "转码产物暂时不可读取");
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(503, "读取已中断");
        } catch (IOException e) {
            throw fail(503, "转码产物读取失败");
        }
    }

    static ResponseStatusException fail(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
}
