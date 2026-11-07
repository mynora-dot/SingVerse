package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class MediaPlaybackServiceTest {
    private HttpServer server;
    private MediaPlaybackService service;
    private volatile Consumer<HttpExchange> handler;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/dav/song.mp4", exchange -> {
            requests.incrementAndGet();
            try { handler.accept(exchange); } finally { exchange.close(); }
        });
        server.start();
        var songs = mock(MediaSongRepository.class);
        when(songs.get("song")).thenReturn(new Song("song", "source", "source", "song.mp4", 100L,
                null, null, null, 1, null, null, null, null, null));
        var sources = mock(MediaSourceRepository.class);
        when(sources.get("source")).thenReturn(new Source("source", "source", "WEBDAV",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/dav", "test", "profile",
                true, null, null, false, null, null, null, null, null));
        when(sources.find("source")).thenReturn(new java.util.HashMap<>(Map.of("password_encrypted", "encrypted",
                "location", "http://127.0.0.1:" + server.getAddress().getPort() + "/dav", "username", "test", "source_type", "WEBDAV")));
        var crypto = mock(AuthCrypto.class);
        when(crypto.decrypt("encrypted", "singverse:webdav:v1")).thenReturn("isolated-password");
        service = new MediaPlaybackService(songs, sources, crypto);
    }

    @AfterEach void stop() { server.stop(0); }

    private void upstream(HttpExchange exchange, int status, String contentRange, byte[] body) {
        try {
            if (contentRange != null) exchange.getResponseHeaders().set("Content-Range", contentRange);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    @Test void exactRangeForwardsOnlyRequestedBytesAndCredentialsStayOnServer() throws Exception {
        handler = exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Range")).isEqualTo("bytes=90-200");
            assertThat(exchange.getRequestHeaders().getFirst("Accept-Encoding")).isEqualTo("identity");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
            upstream(exchange, 206, "bytes 90-99/100", new byte[10]);
        };
        var response = new MockHttpServletResponse();
        service.play("song", "bytes=90-200", false, response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader("Content-Range")).isEqualTo("bytes 90-99/100");
        assertThat(response.getContentLengthLong()).isEqualTo(10);
        assertThat(response.getContentAsByteArray()).hasSize(10);
        assertThat(response.getHeader("Authorization")).isNull();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void suffixAndOpenEndedRangesAreValidatedNumerically() throws Exception {
        handler = exchange -> upstream(exchange, 206, "bytes 90-99/100", new byte[10]);
        for (String range : new String[]{"bytes=-10", "bytes=90-"}) {
            var response = new MockHttpServletResponse();
            service.play("song", range, false, response);
            assertThat(response.getContentAsByteArray()).hasSize(10);
        }
    }

    @Test void wrongOffsetOrImpossibleRangeIsRejectedBeforeMediaIsExposed() {
        for (String returned : new String[]{"bytes 0-9/100", "bytes 90-98/100", "bytes 90-100/100",
                "bytes 99-90/100", "bytes 90-99/0", "bytes 90-99/999999999999999999999", "bytes 90-99/*"}) {
            handler = exchange -> upstream(exchange, 206, returned, new byte[10]);
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> service.play("song", "bytes=90-99", false, response))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502", "无效的媒体范围");
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
    }

    @Test void partialResponseWithoutRequestedRangeIsRejected() {
        handler = exchange -> upstream(exchange, 206, "bytes 0-9/100", new byte[10]);
        assertThatThrownBy(() -> service.play("song", null, false, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("502");
    }

    @Test void inconsistentLengthIsRejected() {
        handler = exchange -> upstream(exchange, 206, "bytes 90-99/100", new byte[9]);
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.play("song", "bytes=90-99", false, response))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不一致的媒体长度");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test void ignoredSeekRangeDoesNotDownloadAndDiscardFilePrefix() {
        handler = exchange -> upstream(exchange, 200, null, new byte[100]);
        for (String range : new String[]{"bytes=90-", "bytes=-10"}) {
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> service.play("song", range, false, response))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不支持媒体字节范围");
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
    }

    @Test void serverWithoutRangesCanStillStartAtZero() throws Exception {
        handler = exchange -> upstream(exchange, 200, null, new byte[100]);
        for (String range : new String[]{null, "bytes=0-", "bytes=0-9"}) {
            var response = new MockHttpServletResponse();
            service.play("song", range, false, response);
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsByteArray()).hasSize(100);
        }
    }

    @Test void compressedResponseIsNotTreatedAsMediaBytes() {
        handler = exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            upstream(exchange, 206, "bytes 90-99/100", new byte[10]);
        };
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.play("song", "bytes=90-99", false, response))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("压缩的媒体响应");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test void headMayIgnoreRangeWithoutDownloadingAnyBody() throws Exception {
        handler = exchange -> {
            try {
                assertThat(exchange.getRequestMethod()).isEqualTo("HEAD");
                exchange.getResponseHeaders().set("Content-Length", "100");
                exchange.sendResponseHeaders(200, -1);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
        };
        var response = new MockHttpServletResponse();
        service.play("song", "bytes=90-", true, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLengthLong()).isEqualTo(100);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test void redirectIsNeverFollowedWithSourceCredentials() {
        handler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            upstream(exchange, 302, null, new byte[0]);
        };
        var targetRequests = new AtomicInteger();
        server.createContext("/target", exchange -> { targetRequests.incrementAndGet(); exchange.close(); });
        assertThatThrownBy(() -> service.play("song", null, false, new MockHttpServletResponse()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("重定向");
        assertThat(targetRequests.get()).isZero();
    }
}
