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
        server.createContext("/internal/v1/media/tags", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + Files.readString(token));
            assertThat(new ObjectMapper().readTree(exchange.getRequestBody()).path("path").asText()).isEqualTo(temp.resolve("song.mkv").toString());
            byte[] bytes = (status.get() == 201 ? "x".repeat(70000) : "{\"title\":\"文件歌名\",\"artists\":[\"歌手\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get() == 201 ? 200 : status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var client = new MediaTagsClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), token.toString());
            assertThat(client.hasIdentity(client.read(temp.resolve("song.mkv")))).isTrue();
            for (int failure : new int[]{401, 403, 429, 503, 201}) {
                status.set(failure); assertThat(client.read(temp.resolve("song.mkv"))).isNull();
            }
        } finally { server.stop(0); }
        assertThat(new MediaTagsClient(new ObjectMapper(), "", token.toString()).read(temp.resolve("song.mkv"))).isNull();
    }
}
