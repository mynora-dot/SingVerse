package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.mynora.singverse.media.transcoding.SongCacheService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** Real WebDAV responses isolate optional cache failures from source and authorization failures. */
class MediaPlaybackCacheFailureTest {
    private final byte[] body = new byte[512 * 1024 + 37];
    private final List<String> requestsA = new CopyOnWriteArrayList<>();
    private final List<String> requestsB = new CopyOnWriteArrayList<>();
    private HttpServer serverA, serverB;
    private MediaPlaybackService service;
    private MediaSourceRepository sources;
    private SongCacheService cache;
    private SongCacheService.Original original;

    @BeforeEach void start() throws Exception {
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i % 251);
        serverA = server(requestsA);
        serverB = server(requestsB);
        var songs = mock(MediaSongRepository.class);
        when(songs.get("song")).thenReturn(new Song("song", "source", "fixture", "song.mp4", (long) body.length,
                "2026-11-07T00:00:00Z", "scan-v1", null, 0, null, null, null, null, null));
        sources = mock(MediaSourceRepository.class);
        when(sources.get("source")).thenReturn(new Source("source", "fixture", "WEBDAV", location(serverA),
                "fixture-user-a", null, true, null, "revision-a", false, null, null, null, null, null));
        when(sources.find("source")).thenReturn(sourceRow(serverA, "fixture-user-a", "encrypted-a", "revision-a"));
        var crypto = mock(AuthCrypto.class);
        when(crypto.decrypt("encrypted-a", "singverse:webdav:v1")).thenReturn("fixture-password-a");
        when(crypto.decrypt("encrypted-b", "singverse:webdav:v1")).thenReturn("fixture-password-b");
        cache = mock(SongCacheService.class);
        original = mock(SongCacheService.Original.class);
        service = new MediaPlaybackService(songs, sources, crypto, cache, null, new ObjectMapper());
    }

    @AfterEach void stop() {
        if (service != null) service.closePrefetch();
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
    }

    private HttpServer server(List<String> requests) throws IOException {
        var result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/dav/song.mp4", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try { upstream(exchange); } finally { exchange.close(); }
        });
        result.start();
        return result;
    }

    private void upstream(HttpExchange exchange) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0, end = body.length - 1;
        if (range != null) {
            var parts = range.substring(6).split("-", -1);
            start = Integer.parseInt(parts[0]);
            if (!parts[1].isEmpty()) end = Math.min(end, Integer.parseInt(parts[1]));
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + body.length);
        }
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
        try (var output = exchange.getResponseBody()) { output.write(body, start, end - start + 1); }
    }

    private String location(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/dav"; }

    private Map<String, Object> sourceRow(HttpServer server, String username, String encrypted, String revision) {
        return Map.of("source_type", "WEBDAV", "location", location(server), "username", username,
                "password_encrypted", encrypted, "updated_at", revision);
    }

    private DataAccessResourceFailureException cacheFailure() {
        return new DataAccessResourceFailureException("simulated optional cache failure");
    }

    @Test void cancelThenImmediateSameSongRegistrationKeepsTheNewTarget() {
        service.prefetchOriginal("song", "replacement", () -> true);
        // Force registration at the protection-release boundary, before closing the previous socket.
        doAnswer(call -> {
            service.prefetchOriginal("song", "replacement", () -> true);
            return null;
        }).when(cache).protectPrefetch("replacement", null);

        service.cancelOriginalPrefetch("replacement");

        assertThat(service.originalPrefetchStatus("replacement").path("state").asText()).isEqualTo("QUEUED");
        assertThat(service.originalPrefetchStatus("replacement").path("songId").asText()).isEqualTo("song");
        var order = inOrder(cache);
        order.verify(cache).protectPrefetch("replacement", "song");
        order.verify(cache).protectPrefetch("replacement", null);
        order.verify(cache).protectPrefetch("replacement", "song");
    }

    private void cachedSource() {
        when(cache.original(eq("song"), anyString(), anyLong(), anyBoolean())).thenReturn(original);
        when(original.partialUsable()).thenReturn(true);
        when(original.sourceBytes()).thenReturn((long) body.length);
        when(original.blockEnd(anyLong())).thenReturn(body.length - 1L);
        when(original.missingEnd(anyLong(), anyLong())).thenAnswer(call -> call.getArgument(1));
    }

    private void assertOriginalBody() throws Exception {
        var response = new MockHttpServletResponse();
        service.play("song", null, false, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(body);
        assertThat(response.getContentLengthLong()).isEqualTo(body.length);
        assertThat(response.getHeader("Authorization")).isNull();
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "fixture-user-a:fixture-password-a".getBytes(StandardCharsets.UTF_8));
        assertThat(requestsA).containsExactly(expected);
        assertThat(requestsB).isEmpty();
    }

    @Test void discoveredRangeMetadataWriteFailureStillReturnsAllSourceBytes() throws Exception {
        when(cache.original(eq("song"), anyString(), anyLong(), anyBoolean())).thenReturn(null, null, original);
        doThrow(cacheFailure()).when(original).rangeSupported();
        assertOriginalBody();
        verify(original).rangeSupported();
        verify(original).close();
    }

    @Test void mixedRangeMetadataWriteFailureStillReturnsAllSourceBytes() throws Exception {
        cachedSource();
        doThrow(cacheFailure()).when(original).rangeSupported();
        assertOriginalBody();
        verify(original).rangeSupported();
    }

    @Test void cacheInitializationFailureStillReturnsAllSourceBytes() throws Exception {
        when(cache.original(eq("song"), anyString(), anyLong(), anyBoolean())).thenThrow(cacheFailure());
        assertOriginalBody();
    }

    @Test void cachePresenceAndMissingIndexFailuresStillReturnAllSourceBytes() throws Exception {
        cachedSource();
        when(original.present(anyLong())).thenThrow(cacheFailure());
        when(original.missingEnd(anyLong(), anyLong())).thenThrow(cacheFailure());
        assertOriginalBody();
        verify(original).present(0);
        verify(original).missingEnd(0, body.length - 1L);
    }

    @Test void corruptCachedBlockIsReadFromTheSourceWithoutDuplicatingBytes() throws Exception {
        cachedSource();
        when(original.present(anyLong())).thenReturn(true);
        when(original.read(anyLong(), anyLong(), any(Runnable.class))).thenThrow(new IOException("cached block truncated"));
        assertOriginalBody();
        verify(original).read(eq(0L), eq(body.length - 1L), any(Runnable.class));
    }

    @Test void cancelledReadGuardCannotBeTreatedAsACacheFailureAndOpenTheSource() throws Exception {
        cachedSource();
        when(original.present(anyLong())).thenReturn(true);
        var active = new AtomicBoolean(true);
        when(original.read(anyLong(), anyLong(), any(Runnable.class))).thenAnswer(call -> {
            active.set(false);
            call.<Runnable>getArgument(2).run();
            throw new AssertionError("The cancelled grant must reject the cached read");
        });
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.play("song", null, false, response, () -> {
            if (!active.get()) throw new CancellationException("fixture playback was cancelled");
        })).isInstanceOf(CancellationException.class);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(requestsA).isEmpty();
        assertThat(requestsB).isEmpty();
    }

    @Test void changedSourceCannotSendNewCredentialsToThePreviousAddress() {
        when(sources.find("source")).thenReturn(sourceRow(serverB, "fixture-user-b", "encrypted-b", "revision-b"));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> service.play("song", null, false, response))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(requestsA).isEmpty();
        assertThat(requestsB).isEmpty();
    }
}
