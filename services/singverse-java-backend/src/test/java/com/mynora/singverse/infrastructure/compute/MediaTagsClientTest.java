package com.mynora.singverse.infrastructure.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class MediaTagsClientTest {
    @TempDir Path temp;
    @Test void authenticatesAndBoundsResponsesWhileFailureFallsBack() throws Exception {
        Path token = temp.resolve("token"); Files.writeString(token, "isolated-token-012345678901234567890123456789");
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/media/remote-tags", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + Files.readString(token));
            var payload=new ObjectMapper().readTree(exchange.getRequestBody());
            assertThat(payload.has("path")).isFalse();assertThat(payload.path("reference").asText()).matches("[a-f0-9-]{36}");
            assertThat(payload.path("callbackOrigin").asText()).isEqualTo("http://127.0.0.1:18080");
            byte[] bytes = (status.get() == 201 ? "x".repeat(4*1024*1024+1) : "{\"tags\":{\"title\":\"文件歌名\",\"artists\":[\"歌手\"]}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get() == 201 ? 200 : status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var client = new MediaTagsClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), token.toString());
            Files.writeString(temp.resolve("song.mkv"),"fixture");
            var env=new org.springframework.mock.env.MockEnvironment().withProperty("singverse.compute.callback-origin","http://127.0.0.1:18080");
            var callback=new com.mynora.singverse.compute.ComputeCallback(new com.mynora.singverse.auth.security.AuthHttp(env),env);
            org.springframework.test.util.ReflectionTestUtils.setField(client,"callback",callback);
            org.springframework.test.util.ReflectionTestUtils.setField(client,"input",new com.mynora.singverse.compute.LocalMetadataInput());
            assertThat(client.hasIdentity(client.read(temp.resolve("song.mkv")))).isTrue();
            for (int failure : new int[]{401, 403, 429, 503, 201}) {
                status.set(failure); assertThat(client.read(temp.resolve("song.mkv"))).isNull();
            }
        } finally { server.stop(0); }
        assertThat(new MediaTagsClient(new ObjectMapper(), "", token.toString()).read(temp.resolve("song.mkv"))).isNull();
    }
}
