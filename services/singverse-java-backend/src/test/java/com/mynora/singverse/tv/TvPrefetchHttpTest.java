package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Real HTTP authorization, SQLite/XML queue commits and speculative-cache registration boundary. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class TvPrefetchHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("prefetch.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        p.add("singverse.transcoding.cache-minimum-free-bytes", () -> 0);
        p.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        p.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        p.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        p.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MediaSongRepository songs;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixture;
    @MockitoSpyBean MediaPlaybackService playback;
    HttpClient admin, client;
    String token, sid, base, owner, source;
    List<String> songIds;
    JsonNode snapshot;
    final Map<String, BooleanSupplier> guards = new ConcurrentHashMap<>();

    HttpResponse<String> request(String path, String method, Object body, String bearer, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (csrf) builder.header("X-CSRF-Token", json.readTree(request("/api/v1/auth/csrf", "GET", null, null, false).body()).path("token").asText());
        if (body != null) builder.header("Content-Type", "application/json");
        return (bearer == null ? admin : client).send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path, String method, Object body, String bearer, boolean csrf) throws Exception {
        var result = request(path, method, body, bearer, csrf);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
        return json.readTree(result.body());
    }
    JsonNode command(String action, Map<String, Object> fields, String bearer, boolean party) throws Exception {
        var body = new HashMap<String, Object>(fields);
        body.put("requestId", UUID.randomUUID().toString()); body.put("action", action);
        body.put("expectedRevision", snapshot.path("revision").asLong());
        snapshot = ok((party ? "/api/v1/party/sessions/" + sid : base) + "/commands", "POST", body, bearer, false);
        return snapshot;
    }
    JsonNode event(long seq, String state, long position, Long duration) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("attemptId", snapshot.path("playback").path("attemptId").asText());
        body.put("eventSeq", seq); body.put("state", state); body.put("positionMs", position);
        if (duration != null) body.put("durationMs", duration);
        snapshot = ok(base + "/playback-events", "POST", body, token, false);
        return snapshot;
    }

    @BeforeEach void party() throws Exception {
        admin = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        client = HttpClient.newHttpClient();
        doAnswer(call -> { guards.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(playback).prefetchOriginal(anyString(), anyString(), any());
        ok("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), null, true);
        var grant = ok("/api/v1/device-authorizations", "POST", Map.of("name", "预缓存测试电视"), null, false);
        ok("/api/v1/admin/device-authorizations/" + grant.path("id").asText() + "/approve", "POST", Map.of("code", grant.path("code").asText()), null, true);
        token = ok("/api/v1/device-authorizations/" + grant.path("id").asText() + "/poll", "POST", Map.of("pollSecret", grant.path("pollSecret").asText()), null, false).path("token").asText();
        snapshot = ok("/api/v1/tv/sessions", "POST", null, token, false);
        sid = snapshot.path("id").asText(); base = "/api/v1/tv/sessions/" + sid; owner = "tv:" + sid;
        source = UUID.randomUUID().toString(); String now = "2026-11-07T00:00:00Z";
        fixture.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                source, "隔离 WebDAV", "WEBDAV", "http://127.0.0.1:1/dav/", source, "", now, now);
        var entries = new ArrayList<MediaSongRepository.Stored>();
        songIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String song = UUID.randomUUID().toString(), name = "歌手-歌曲" + i + ".mp4";
            songIds.add(song);
            entries.add(new MediaSongRepository.Stored(song, source, name, song, 10_000_000L, now, now, "default", 0, null,
                    songs.encode(new KtvFilenameParser().parse(name)), false));
        }
        songs.replace(source, entries);
        for (String song : songIds) command("ADD", Map.of("songId", song), token, false);
        command("START", Map.of(), token, false);
    }

    @Test void progressStartsOnlyTheNextWaitingOriginalAndStaleOrMalformedEventsCannotRepeatIt() throws Exception {
        String attempt = snapshot.path("playback").path("attemptId").asText();
        event(1, "PREPARING", 170_000, 200_000L);
        event(2, "PLAYING", 169_999, 200_000L);
        verify(playback, never()).prefetchOriginal(anyString(), eq(owner), any());
        event(3, "PLAYING", 170_000, 200_000L);
        assertThat(snapshot.path("playback").path("durationMs").asLong()).isEqualTo(200_000);
        event(3, "ENDED", 200_000, 200_000L); // Duplicate eventSeq must not alter the queue or prefetch.
        event(4, "PLAYING", 180_000, null);
        verify(playback, times(1)).prefetchOriginal(eq(songIds.get(1)), eq(owner), any());
        verify(playback, never()).prefetchOriginal(eq(songIds.get(2)), eq(owner), any());
        assertThat(snapshot.path("playback").path("attemptId").asText()).isEqualTo(attempt);
        assertThat(snapshot.path("queue").get(0).path("status").asText()).isEqualTo("PLAYING");
        assertThat(snapshot.path("queue").get(1).path("status").asText()).isEqualTo("WAITING");
        assertThat(snapshot.path("commands").size()).isEqualTo(1); // Only the explicit START's LOAD.
        assertThat(request(base + "/playback-events", "POST", Map.of("attemptId", "old", "eventSeq", 99, "state", "PLAYING", "positionMs", 190_000, "durationMs", 200_000), token, false).statusCode()).isEqualTo(409);
        for (Object bad : List.of(0, -1, 86_400_001L, "200000")) {
            assertThat(request(base + "/playback-events", "POST", Map.of("attemptId", attempt, "eventSeq", 5, "state", "PLAYING", "positionMs", 190_000, "durationMs", bad), token, false).statusCode()).isEqualTo(400);
        }
        verify(playback, times(1)).prefetchOriginal(anyString(), eq(owner), any());
    }

    @Test void h5HostSortingAndRemovalReplaceOnlyItsPartyPrefetch() throws Exception {
        var invitation = ok(base + "/invitations", "POST", null, token, false);
        var joined = ok("/api/v1/party/join", "POST", Map.of("invitation", invitation.path("invitation").asText(), "name", "亲友"), null, false);
        String participant = joined.path("token").asText();
        command("HOST", Map.of("participantId", joined.path("id").asText()), token, false);
        event(1, "PLAYING", 175_000, 200_000L);
        var old = guards.get(songIds.get(1));
        String later = snapshot.path("queue").get(2).path("id").asText();
        command("TOP", Map.of("itemId", later), participant, true);
        assertThat(old.getAsBoolean()).isFalse();
        assertThat(guards.get(songIds.get(2)).getAsBoolean()).isTrue();
        command("REMOVE", Map.of("itemId", later), participant, true);
        assertThat(guards.get(songIds.get(2)).getAsBoolean()).isFalse();
        verify(playback, times(1)).prefetchOriginal(eq(songIds.get(1)), eq(owner), any());
        assertThat(request(base + "/playback-events", "POST", Map.of("state", "PLAYING"), participant, false).statusCode()).isEqualTo(403);
        command("CLEAR", Map.of(), token, false);
        assertThat(guards.get(songIds.get(1)).getAsBoolean()).isFalse();
        assertThat(snapshot.path("playback").path("state").asText()).isEqualTo("PLAYING");
    }

    @Test void stopCancelsBeforeIdleAndLatePlayingCannotRestartTheDownload() throws Exception {
        event(1, "PLAYING", 175_000, 200_000L);
        var old = guards.get(songIds.get(1));
        command("STOP", Map.of(), token, false);
        verify(playback).cancelOriginalPrefetch(owner);
        assertThat(old.getAsBoolean()).isFalse();
        event(2, "PLAYING", 180_000, 200_000L);
        verify(playback, times(1)).prefetchOriginal(anyString(), eq(owner), any());
        command("RESTART", Map.of(), token, false);
        assertThat(snapshot.path("playback").has("durationMs")).isFalse();
        event(1, "PLAYING", 175_000, 200_000L);
        verify(playback, times(2)).prefetchOriginal(eq(songIds.get(1)), eq(owner), any());
        snapshot = ok(base + "/recover", "POST", null, token, false);
        assertThat(guards.get(songIds.get(1)).getAsBoolean()).isFalse();
        assertThat(snapshot.has("playback")).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"NEXT", "END", "REVOKE"})
    void transitionOrRevocationReleasesTheOldReference(String action) throws Exception {
        event(1, "PLAYING", 175_000, 200_000L);
        var old = guards.get(songIds.get(1));
        if (action.equals("REVOKE")) {
            ok("/api/v1/admin/tv-devices/" + snapshot.path("deviceId").asText(), "DELETE", null, null, true);
            assertThat(request(base + "/playback-events", "POST", Map.of("state", "PLAYING"), token, false).statusCode()).isEqualTo(401);
        } else {
            command(action, Map.of(), token, false);
            if (action.equals("NEXT")) {
                assertThat(snapshot.path("playback").path("confirmed").asBoolean()).isFalse();
                assertThat(snapshot.path("commands").get(0).path("action").asText()).isEqualTo("STOP");
            } else assertThat(snapshot.path("status").asText()).isEqualTo("ENDED");
        }
        assertThat(old.getAsBoolean()).isFalse();
        verify(playback).cancelOriginalPrefetch(owner);
        verify(playback, times(1)).prefetchOriginal(anyString(), eq(owner), any());
    }

    @Test void cacheRegistrationFailureLeavesCurrentPlaybackAndQueueUnchanged() throws Exception {
        doThrow(new IllegalStateException("cache full")).when(playback).prefetchOriginal(anyString(), eq(owner), any());
        event(1, "PLAYING", 175_000, 200_000L);
        event(2, "PLAYING", 180_000, 200_000L);
        assertThat(snapshot.path("playback").path("state").asText()).isEqualTo("PLAYING");
        assertThat(snapshot.path("queue").get(0).path("status").asText()).isEqualTo("PLAYING");
        assertThat(snapshot.path("queue").get(1).path("status").asText()).isEqualTo("WAITING");
        verify(playback, times(1)).prefetchOriginal(anyString(), eq(owner), any());
    }

    @Test void committedNearEndEventFetchesRealWebDavHeadAndStopClosesTheLivePrefetch() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        var tailStarted = new CountDownLatch(1);
        var releaseTail = new CountDownLatch(1);
        var disconnected = new CountDownLatch(1);
        var requested = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final int block = 2 * 1024 * 1024, total = 10_000_000;
        byte[] head = new byte[block]; Arrays.fill(head, (byte) 0x5a);
        server.setExecutor(executor);
        server.createContext("/dav/", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            requested.add(exchange.getRequestURI().getPath() + ":" + range);
            if (!"GET".equals(exchange.getRequestMethod()) || range == null || !range.matches("bytes=[0-9]+-[0-9]+")) {
                exchange.sendResponseHeaders(400, -1); exchange.close(); return;
            }
            String[] bounds = range.substring(6).split("-");
            long start = Long.parseLong(bounds[0]), end = Long.parseLong(bounds[1]);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
            exchange.sendResponseHeaders(206, end - start + 1);
            try (var output = exchange.getResponseBody()) {
                if (start == 0) output.write(head);
                else {
                    byte[] slow = new byte[1024];
                    output.write(slow); output.flush(); tailStarted.countDown();
                    try { releaseTail.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (System.nanoTime() < deadline) {
                        output.write(slow); output.flush();
                        try { Thread.sleep(20); }
                        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                }
            } catch (IOException closed) { if (start > 0) disconnected.countDown(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            fixture.update("UPDATE media_source SET location=? WHERE id=?", "http://127.0.0.1:" + server.getAddress().getPort() + "/dav/", source);
            doCallRealMethod().when(playback).prefetchOriginal(anyString(), eq(owner), any());
            event(1, "PLAYING", 169_999, 200_000L);
            assertThat(requested).isEmpty();
            event(2, "PLAYING", 170_000, 200_000L);
            assertThat(tailStarted.await(10, TimeUnit.SECONDS)).as("scheduled worker fetched the committed next song").isTrue();
            assertThat(playback.originalPrefetchStatus(owner).path("cachedBytes").asLong()).isEqualTo(block);
            var list = ok("/api/v1/transcoding/cache", "GET", null, null, false);
            var entry = java.util.stream.StreamSupport.stream(list.path("items").spliterator(), false)
                    .filter(item -> item.path("songId").asText().equals(songIds.get(1))).findFirst().orElseThrow();
            assertThat(entry.path("target").path("mode").asText()).isEqualTo("ORIGINAL");
            assertThat(entry.path("cachedBytes").asLong()).isEqualTo(block);
            assertThat(entry.path("sourceBytes").asLong()).isEqualTo(total);
            assertThat(entry.path("complete").asBoolean()).isFalse();
            long stopStarted = System.nanoTime();
            assertThat(releaseTail.getCount()).isEqualTo(1);
            command("STOP", Map.of(), token, false);
            assertThat(System.nanoTime() - stopStarted).as("STOP must not wait for the prefetch read timeout")
                    .isLessThan(TimeUnit.SECONDS.toNanos(2));
            releaseTail.countDown();
            assertThat(disconnected.await(5, TimeUnit.SECONDS)).as("STOP closes an active WebDAV response before IDLE").isTrue();
            assertThat(playback.originalPrefetchStatus(owner).path("state").asText()).isEqualTo("CANCELLED");
            assertThat(snapshot.path("playback").path("state").asText()).isEqualTo("PLAYING");
            assertThat(snapshot.path("queue").get(1).path("status").asText()).isEqualTo("WAITING");
            int reads = requested.size();
            var hit = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tv/media/" + songIds.get(1)))
                    .header("Authorization", "Bearer " + token).header("Range", "bytes=0-63").build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(hit.statusCode()).isEqualTo(206);
            assertThat(hit.body()).isEqualTo(Arrays.copyOf(head, 64));
            assertThat(requested).hasSize(reads).allMatch(path -> path.startsWith("/dav/歌手-歌曲1.mp4:"));
        } finally {
            releaseTail.countDown();
            playback.cancelOriginalPrefetch(owner);
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
