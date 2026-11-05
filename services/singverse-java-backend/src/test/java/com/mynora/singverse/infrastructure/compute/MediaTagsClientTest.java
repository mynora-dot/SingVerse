package com.mynora.singverse.infrastructure.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void preservesBoundedLyricsWithoutChangingIdentityFields() throws Exception {
        var json = new ObjectMapper();
        Path token = temp.resolve("lyrics-token");
        Files.writeString(token, "isolated-token-012345678901234567890123456789");
        Path file = Files.writeString(temp.resolve("song.mp3"), "fixture");
        var response = new AtomicReference<>("{\"tags\":{},\"lyrics\":{\"timing\":\"milliseconds\",\"lines\":[{\"time\":1234,\"text\":\"真实逐行歌词\"}]}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/media/remote-tags", exchange -> {
            byte[] bytes = response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var client = new MediaTagsClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), token.toString());
            var env = new org.springframework.mock.env.MockEnvironment().withProperty("singverse.compute.callback-origin", "http://127.0.0.1:18080");
            org.springframework.test.util.ReflectionTestUtils.setField(client, "callback", new com.mynora.singverse.compute.ComputeCallback(new com.mynora.singverse.auth.security.AuthHttp(env), env));
            org.springframework.test.util.ReflectionTestUtils.setField(client, "input", new com.mynora.singverse.compute.LocalMetadataInput());
            String encoded = client.read(file);
            assertThat(json.readTree(encoded).at("/lyrics/lines/0/time").asInt()).isEqualTo(1234);
            assertThat(json.readValue(encoded, MediaTagsClient.Tags.class).fields()).isEmpty();
            assertThat(client.hasIdentity(encoded)).isFalse();

            var result = json.createObjectNode();
            result.putObject("tags").put("title", "歌名").putArray("artists").add("歌手");
            result.putObject("lyrics").put("timing", "lrc").put("text", "[00:01.00]内嵌歌词");
            response.set(json.writeValueAsString(result));
            encoded = client.read(file);
            assertThat(json.readTree(encoded).at("/lyrics/text").asText()).isEqualTo("[00:01.00]内嵌歌词");
            assertThat(json.readValue(encoded, MediaTagsClient.Tags.class).fields()).containsExactlyInAnyOrder("title", "artists");
            assertThat(client.hasIdentity(encoded)).isTrue();

            result.withObject("/lyrics").put("text", "字".repeat(100000));
            response.set(json.writeValueAsString(result));
            encoded = client.read(file);
            assertThat(client.hasIdentity(encoded)).isTrue();
            assertThat(json.readTree(encoded).has("lyrics")).isFalse();
            result.set("lyrics", json.createArrayNode().add("invalid"));
            response.set(json.writeValueAsString(result));
            assertThat(json.readTree(client.read(file)).has("lyrics")).isFalse();

            assertThat(json.readTree(json.writeValueAsString(new MediaTagsClient.Tags("歌名", java.util.List.of("歌手"), null, null))).has("lyrics")).isFalse();
        } finally { server.stop(0); }
    }
}
