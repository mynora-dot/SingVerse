package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.playbackhistory.PlaybackActivityService;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.transcoding.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual HTTP streams and SQLite/XML persistence, without modifying a media library. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OriginalMediaCacheHttpTest {
    @TempDir static Path root;
    static final long BLOCK = SongCacheService.ORIGINAL_BLOCK_BYTES;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> root.resolve("original.db").toString());
        p.add("singverse.auth.key-file", () -> root.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> root.resolve("cache").toString());
        p.add("singverse.transcoding.cache-bytes", () -> 64L * 1024 * 1024);
        p.add("singverse.transcoding.cache-minimum-free-bytes", () -> 0);
        p.add("singverse.transcoding.directory", () -> root.resolve("versions").toString());
        p.add("singverse.audio.directory", () -> root.resolve("audio").toString());
        p.add("singverse.webdav.metadata-path", () -> root.resolve("webdav").toString());
        p.add("singverse.metadata.cache-path", () -> root.resolve("covers").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }
    @LocalServerPort int port;
    @Autowired MediaPlaybackService playback;
    @Autowired SongCacheService cache;
    @Autowired PlaybackActivityService activity;
    @Autowired ObjectMapper json;
    @Autowired AuthCrypto crypto;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource database;
    @Autowired @Qualifier("originalPrefetchExecutor") AsyncTaskExecutor executor;
    @MockitoBean MediaSongRepository songs;
    @MockitoBean MediaSourceRepository sources;
    @MockitoSpyBean SongCacheMapper mapper;
    HttpServer upstream;
    ExecutorService httpWorkers;
    volatile Consumer<HttpExchange> handler;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<CountDownLatch> gates = new ArrayList<>();
    volatile long bytes;
    volatile Long indexedSize;
    volatile String revision;
    HttpClient client;

    @BeforeEach void start() throws Exception {
        for (String owner : List.of("test", "duplicate", "live")) playback.cancelOriginalPrefetch(owner);
        drainPrefetch();
        bytes = 6 * BLOCK; indexedSize = bytes; revision = "v1";
        requests.clear();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpWorkers = Executors.newVirtualThreadPerTaskExecutor(); upstream.setExecutor(httpWorkers);
        upstream.createContext("/dav/song.mp4", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + Objects.toString(exchange.getRequestHeaders().getFirst("Range"), "none"));
            try { handler.accept(exchange); } catch (UncheckedIOException cancelled) { /* A cancelled player closes its socket. */ }
            finally { exchange.close(); }
        });
        upstream.start(); handler = this::normal;
        when(songs.get(anyString())).thenAnswer(call -> new Song(call.getArgument(0), "source", "fixture", "song.mp4", indexedSize,
                "2026-11-07T00:00:00Z", "scan-v1", null, 0, null, new com.mynora.singverse.media.filename.KtvFilenameParser().parse("测试-歌曲.mp4"), null, null, null));
        when(sources.get("source")).thenAnswer(call -> new Source("source", "fixture", "WEBDAV", "http://127.0.0.1:"
                + upstream.getAddress().getPort() + "/dav", "", null, false, null, revision, false, null, null, null, null, null));
        when(sources.find("source")).thenReturn(Map.of());
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> {
            activity.backfillBatch(); assertThat(activity.isReady()).isTrue();
        });
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(7)).untilAsserted(() -> {
            var cleared = cache.clear();
            assertThat(cleared.path("skipped").asInt()).isZero(); assertThat(cleared.path("failed").asInt()).isZero();
            assertThat(cache.list(0, 20).path("entryCount").asInt()).isZero();
        });
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) cache.list(0, 20).path("settings");
        settings.put("maxBytes", 64L * 1024 * 1024).put("policy", "LRU"); cache.save(settings);
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var csrf = json.readTree(client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        assertThat(client.send(HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"admin\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
    @AfterEach void stop() throws Exception {
        for (String owner : List.of("test", "duplicate", "live")) playback.cancelOriginalPrefetch(owner);
        gates.forEach(CountDownLatch::countDown);
        try { drainPrefetch(); }
        finally {
            if (upstream != null) upstream.stop(0);
            if (httpWorkers != null) { httpWorkers.shutdownNow(); httpWorkers.awaitTermination(2, TimeUnit.SECONDS); }
        }
    }
    // The dedicated executor is single-threaded: its barrier precedes Mockito's after-test reset.
    void drainPrefetch() throws Exception { executor.submit(() -> { }).get(7, TimeUnit.SECONDS); }
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    HttpRequest get(String range) { var builder = HttpRequest.newBuilder(uri("/api/v1/media-songs/song/video")).timeout(Duration.ofSeconds(20)); if (range != null) builder.header("Range", range); return builder.GET().build(); }
    HttpResponse<byte[]> read(String range) throws Exception { return client.send(get(range), HttpResponse.BodyHandlers.ofByteArray()); }
    HttpResponse<byte[]> readSong(String song, String range) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/media-songs/" + song + "/video")).timeout(Duration.ofSeconds(20));
        if (range != null) request.header("Range", range);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    HttpResponse<String> write(String path, String method) throws Exception {
        String csrf = json.readTree(client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    void manualPolicy() {
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) cache.list(0, 20).path("settings");
        settings.put("policy", "MANUAL"); cache.save(settings);
    }
    com.fasterxml.jackson.databind.JsonNode item(String song) {
        for (var item : cache.list(0, 20).path("items")) if (item.path("songId").asText().equals(song)) return item;
        throw new AssertionError("Missing cache fixture " + song);
    }
    SongCacheService restartedCache() {
        return new SongCacheService(mock(TranscodeClient.class), mapper, songs, json, transactions,
                root.resolve("cache").toString(), 64L * 1024 * 1024, 0);
    }
    void cachedPrefix(SongCacheService restarted, String song) throws Exception {
        var response = new MockHttpServletResponse();
        new MediaPlaybackService(songs, sources, crypto, restarted, executor, json).play(song, "bytes=0-63", false, response);
        assertThat(response.getContentAsByteArray()).hasSize(64);
    }
    CountDownLatch gate() { var gate = new CountDownLatch(1); gates.add(gate); return gate; }
    void await(Callable<Boolean> condition) { org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(condition); }
    long[] interval(HttpExchange exchange) {
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range == null) return new long[]{0, bytes - 1};
        var parts = range.substring(6).split("-", -1);
        return new long[]{parts[0].isEmpty() ? Math.max(0, bytes - Long.parseLong(parts[1])) : Long.parseLong(parts[0]),
                parts[0].isEmpty() || parts[1].isEmpty() ? bytes - 1 : Math.min(bytes - 1, Long.parseLong(parts[1]))};
    }
    void normal(HttpExchange exchange) {
        try {
            var range = interval(exchange); boolean partial = exchange.getRequestHeaders().containsKey("Range");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (partial) exchange.getResponseHeaders().set("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + bytes);
            long length = range[1] - range[0] + 1;
            if (exchange.getRequestMethod().equals("HEAD")) { exchange.getResponseHeaders().set("Content-Length", Long.toString(length)); exchange.sendResponseHeaders(partial ? 206 : 200, -1); return; }
            exchange.sendResponseHeaders(partial ? 206 : 200, length); send(exchange.getResponseBody(), range[0], length);
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    static void send(OutputStream output, long start, long length) throws IOException {
        byte[] buffer = new byte[65536];
        while (length > 0) { int count = (int) Math.min(buffer.length, length); for (int i = 0; i < count; i++) buffer[i] = (byte) ((start + i) % 251); output.write(buffer, 0, count); start += count; length -= count; }
    }

    @Test void twentyBlocksUseOneSourceStreamAndReuseAfterRestartWithoutQuotaRescans() throws Exception {
        bytes = indexedSize = 20 * BLOCK + 123;
        clearInvocations(mapper);
        var first = read(null); assertThat(first.statusCode()).isEqualTo(200); assertThat(first.body()).hasSize((int) bytes);
        assertThat(requests).containsExactly("GET bytes=0-" + (bytes - 1));
        verify(mapper, atMost(3)).entries();
        var item = cache.list(0, 20).path("items").get(0);
        assertThat(item.path("target").path("mode").asText()).isEqualTo("ORIGINAL");
        assertThat(item.path("mediaType").asText()).isEqualTo("VIDEO");
        assertThat(item.path("cachedBytes").asLong()).isEqualTo(bytes); assertThat(item.path("complete").asBoolean()).isTrue();
        var restartedCache = new SongCacheService(mock(TranscodeClient.class), mapper, songs, json, transactions, root.resolve("cache").toString(), 64L * 1024 * 1024, 0);
        var restarted = new MediaPlaybackService(songs, sources, crypto, restartedCache, executor, json);
        var response = new MockHttpServletResponse(); restarted.play("song", "bytes=2097200-2097300", false, response);
        assertThat(response.getContentAsByteArray()).hasSize(101); assertThat(requests).hasSize(1);
    }

    @Test void aLongHttpResponseRollsItsReservationWithoutReservingTheWholeOriginal() throws Exception {
        manualPolicy(); bytes = indexedSize = 48L * 1024 * 1024 + 123;
        var entered = gate(); var release = gate();
        handler = exchange -> {
            try {
                var range = interval(exchange);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + bytes);
                exchange.sendResponseHeaders(206, range[1] - range[0] + 1);
                send(exchange.getResponseBody(), range[0], 65536); exchange.getResponseBody().flush(); entered.countDown();
                release.await(8, TimeUnit.SECONDS);
                send(exchange.getResponseBody(), range[0] + 65536, range[1] - range[0] + 1 - 65536);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        };
        long maximumReserved = 0, transferred = 0;
        try (var body = client.send(get(null), HttpResponse.BodyHandlers.ofInputStream()).body()) {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            byte[] buffer = new byte[65536];
            int count = body.read(buffer); assertThat(count).isPositive(); transferred += count;
            maximumReserved = cache.list(0, 20).path("reservedBytes").asLong();
            assertThat(maximumReserved).isPositive(); release.countDown();
            while ((count = body.read(buffer)) >= 0) {
                for (int i = 0; i < count; i++) if (buffer[i] != (byte) ((transferred + i) % 251)) fail("Original stream differs at " + (transferred + i));
                transferred += count;
                if (transferred % BLOCK < buffer.length)
                    maximumReserved = Math.max(maximumReserved, cache.list(0, 20).path("reservedBytes").asLong());
            }
        }
        assertThat(transferred).isEqualTo(bytes);
        // The owned staging marker is metadata; the rolling media reservation is at most 32 MiB.
        long markerBytes = "SingVerse song cache v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(maximumReserved - markerBytes).isLessThanOrEqualTo(32L * 1024 * 1024);
        var snapshot = cache.list(0, 20);
        assertThat(snapshot.path("reservedBytes").asLong()).isZero();
        assertThat(snapshot.path("items").get(0).path("cachedBytes").asLong()).isEqualTo(bytes);
        assertThat(snapshot.path("items").get(0).path("complete").asBoolean()).isTrue();
        assertThat(requests).containsExactly("GET bytes=0-" + (bytes - 1));
    }

    @Test void freeingCapacityChangesGenerationAndResumesPrefetchBeforeItsThirtySecondRetry() throws Exception {
        manualPolicy(); bytes = indexedSize = 62L * 1024 * 1024;
        assertThat(read(null).body()).hasSize((int) bytes);
        String filler = cache.list(0, 20).path("items").get(0).path("id").asText();
        bytes = indexedSize = 6 * BLOCK;
        playback.prefetchOriginal("other", "test", () -> true);
        await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("PARTIAL"));
        assertThat(playback.originalPrefetchStatus("test").path("error").asText()).contains("等待缓存空间");
        assertThat(requests).hasSize(1);
        long generation = cache.cleanupGeneration();
        assertThat(write("/api/v1/transcoding/cache/" + filler, "DELETE").statusCode()).isEqualTo(204);
        assertThat(cache.cleanupGeneration()).isGreaterThan(generation);
        playback.prefetchTick();
        await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("COMPLETE"));
        var item = cache.list(0, 20).path("items").get(0);
        assertThat(item.path("songId").asText()).isEqualTo("other");
        assertThat(item.path("cachedBytes").asLong()).isEqualTo(bytes);
        int count = requests.size();
        assertThat(readSong("other", "bytes=10-63").body()).hasSize(54);
        assertThat(requests).hasSize(count);
    }

    @Test void completedNextSongStaysProtectedUntilItsPrefetchOwnerIsCancelled() throws Exception {
        bytes = indexedSize = 2 * BLOCK;
        playback.prefetchOriginal("other", "test", () -> true); playback.prefetchTick();
        await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("COMPLETE"));
        await(() -> { var reasons = cache.list(0, 20).path("items").get(0).path("protectionReasons").toString();
            return reasons.contains("PREFETCH") && !reasons.contains("PLAYING") && !reasons.contains("DOWNLOADING"); });
        var item = cache.list(0, 20).path("items").get(0);
        assertThat(item.path("complete").asBoolean()).isTrue();
        assertThat(item.path("protectionReasons").toString()).contains("PREFETCH");
        var protectedClear = write("/api/v1/transcoding/cache/clear", "POST");
        assertThat(protectedClear.statusCode()).isEqualTo(200);
        assertThat(json.readTree(protectedClear.body()).path("skippedByReason").path("PREFETCH").asInt()).isEqualTo(1);
        int count = requests.size();
        assertThat(readSong("other", "bytes=10-63").body()).hasSize(54); assertThat(requests).hasSize(count);
        playback.cancelOriginalPrefetch("test");
        var releasedClear = write("/api/v1/transcoding/cache/clear", "POST");
        assertThat(releasedClear.statusCode()).isEqualTo(200);
        assertThat(json.readTree(releasedClear.body()).path("removed").asInt()).isEqualTo(1);
        assertThat(cache.list(0, 20).path("entryCount").asInt()).isZero();
        assertThat(readSong("other", "bytes=10-63").body()).hasSize(54);
        assertThat(requests).hasSize(count + 1);
    }

    @Test void truncatedBlockRemainsChargedAcrossRestartUntilTheHttpReplacementSucceeds() throws Exception {
        manualPolicy(); bytes = indexedSize = 4 * BLOCK; assertThat(read(null).body()).hasSize((int) bytes);
        String id = item("song").path("id").asText(); Path block = root.resolve("cache").resolve(id).resolve("original_1.block");
        try (var file = java.nio.channels.FileChannel.open(block, StandardOpenOption.WRITE)) { file.truncate(BLOCK / 2); }
        var first = restartedCache(); var second = restartedCache();
        try {
            cachedPrefix(first, "song");
            assertThat(((Number) mapper.find(id).get("bytes")).longValue()).isEqualTo(3 * BLOCK);
            assertThat(((Number) mapper.find(id).get("residue_bytes")).longValue()).isEqualTo(BLOCK / 2);
            long occupied = 3 * BLOCK + BLOCK / 2 + "SingVerse song cache v1\n".length();
            assertThat(first.list(0, 20).path("usageBytes").asLong()).isEqualTo(occupied);
            cachedPrefix(second, "song");
            assertThat(second.list(0, 20).path("usageBytes").asLong()).isEqualTo(occupied);
            assertThat(requests).hasSize(1);
        } finally { first.close(); second.close(); }
        assertThat(read("bytes=" + BLOCK + "-" + (2 * BLOCK - 1)).body()).hasSize((int) BLOCK);
        assertThat(Files.size(block)).isEqualTo(BLOCK);
        assertThat(item("song").path("residueBytes").asLong()).isZero();
        assertThat(item("song").path("cachedBytes").asLong()).isEqualTo(bytes);
        assertThat(item("song").path("complete").asBoolean()).isTrue();
        assertThat(requests).hasSize(2);
    }

    @Test void aHotOversizedBlockIsNotServedOrFreedBeforeAWholeBlockCanReplaceIt() throws Exception {
        manualPolicy(); bytes = indexedSize = 3 * BLOCK; assertThat(read(null).body()).hasSize((int) bytes);
        String id = item("song").path("id").asText(); Path block = root.resolve("cache").resolve(id).resolve("original_1.block");
        Files.write(block, new byte[(int) BLOCK / 2], StandardOpenOption.APPEND);
        var partial = read("bytes=" + (BLOCK + 10) + "-" + (BLOCK + 73));
        assertThat(partial.statusCode()).isEqualTo(206); assertThat(partial.body()).hasSize(64);
        for (int i = 0; i < partial.body().length; i++) assertThat(partial.body()[i]).isEqualTo((byte) ((BLOCK + 10 + i) % 251));
        assertThat(item("song").path("cachedBytes").asLong()).isEqualTo(2 * BLOCK);
        assertThat(item("song").path("residueBytes").asLong()).isEqualTo(BLOCK + BLOCK / 2);
        assertThat(item("song").path("complete").asBoolean()).isFalse();
        assertThat(cache.list(0, 20).path("usageBytes").asLong()).isEqualTo(bytes + BLOCK / 2 + "SingVerse song cache v1\n".length());
        assertThat(Files.size(block)).isEqualTo(BLOCK + BLOCK / 2);
        assertThat(read("bytes=" + BLOCK + "-" + (2 * BLOCK - 1)).body()).hasSize((int) BLOCK);
        assertThat(item("song").path("residueBytes").asLong()).isZero();
        assertThat(item("song").path("complete").asBoolean()).isTrue();
        assertThat(Files.size(block)).isEqualTo(BLOCK);
    }

    @Test void anUnreplaceableInvalidBlockStillStreamsFromHttpAndRetainsItsResidueAfterRestart() throws Exception {
        manualPolicy(); bytes = indexedSize = 4 * BLOCK; assertThat(read(null).body()).hasSize((int) bytes);
        String id = item("song").path("id").asText(); Path block = root.resolve("cache").resolve(id).resolve("original_1.block");
        Files.delete(block); Files.createDirectory(block); Path retained = block.resolve("owned-but-unusable");
        Files.write(retained, new byte[(int) BLOCK / 2]);
        var response = read("bytes=" + BLOCK + "-" + (2 * BLOCK - 1));
        assertThat(response.statusCode()).isEqualTo(206); assertThat(response.body()).hasSize((int) BLOCK);
        assertThat(Files.size(retained)).isEqualTo(BLOCK / 2); // The playback thread did not recursively delete it.
        assertThat(item("song").path("residueBytes").asLong()).isGreaterThanOrEqualTo(BLOCK / 2);
        assertThat(item("song").path("complete").asBoolean()).isFalse();
        var restarted = restartedCache();
        try {
            cachedPrefix(restarted, "song");
            var restored = restarted.list(0, 20).path("items").get(0);
            assertThat(restored.path("cachedBytes").asLong()).isEqualTo(3 * BLOCK);
            assertThat(restored.path("residueBytes").asLong()).isEqualTo(BLOCK / 2);
            assertThat(restarted.list(0, 20).path("usageBytes").asLong()).isEqualTo(3 * BLOCK + BLOCK / 2 + "SingVerse song cache v1\n".length());
        } finally { restarted.close(); }
        assertThat(requests).hasSize(2);
        // Restore the shared fixture through its original instance after the isolated restart check.
        Files.delete(retained); Files.delete(block);
        assertThat(read("bytes=" + BLOCK + "-" + (2 * BLOCK - 1)).body()).hasSize((int) BLOCK);
        assertThat(item("song").path("residueBytes").asLong()).isZero();
    }

    @Test void startupCensusDoesNotLetATruncatedEntryHideAnotherEntriesUnindexedResidue() throws Exception {
        manualPolicy(); bytes = indexedSize = 4 * BLOCK;
        assertThat(read(null).body()).hasSize((int) bytes); assertThat(readSong("other", null).body()).hasSize((int) bytes);
        Path a = root.resolve("cache").resolve(item("song").path("id").asText());
        Path b = root.resolve("cache").resolve(item("other").path("id").asText());
        try (var file = java.nio.channels.FileChannel.open(a.resolve("original_1.block"), StandardOpenOption.WRITE)) { file.truncate(BLOCK / 2); }
        Files.write(b.resolve("original_4.block"), new byte[(int) BLOCK]);
        long physical = 8 * BLOCK + BLOCK / 2 + 2L * "SingVerse song cache v1\n".length();
        var restarted = restartedCache();
        try {
            restarted.startMaintenance(); await(() -> restarted.list(0, 20).path("usageReady").asBoolean());
            assertThat(restarted.list(0, 20).path("usageBytes").asLong()).isGreaterThanOrEqualTo(physical);
            cachedPrefix(restarted, "song"); cachedPrefix(restarted, "other");
            assertThat(restarted.list(0, 20).path("usageBytes").asLong()).isEqualTo(physical);
            assertThat(((Number) mapper.totals().get("media_bytes")).longValue()).isEqualTo(physical - 2L * "SingVerse song cache v1\n".length());
        } finally { restarted.close(); }
        assertThat(requests).hasSize(2);
    }

    @Test void startupCensusAttributesUnindexedResidueSoHttpDeletionReleasesAllBytes() throws Exception {
        manualPolicy(); bytes = indexedSize = 4 * BLOCK; assertThat(read(null).body()).hasSize((int) bytes);
        String id = item("song").path("id").asText(); Path folder = root.resolve("cache").resolve(id);
        Files.write(folder.resolve("original_4.block"), new byte[(int) BLOCK]);
        cache.startMaintenance(); await(() -> cache.list(0, 20).path("usageReady").asBoolean());
        assertThat(cache.list(0, 20).path("usageBytes").asLong()).isEqualTo(5 * BLOCK + "SingVerse song cache v1\n".length());
        assertThat(write("/api/v1/transcoding/cache/" + id, "DELETE").statusCode()).isEqualTo(204);
        assertThat(Files.exists(folder)).isFalse();
        assertThat(cache.list(0, 20).path("usageBytes").asLong()).isZero();
        assertThat(cache.list(0, 20).path("entryCount").asInt()).isZero();
    }

    @Test void cachedHeadAndTailStreamBeforeMissingMiddleAndProtectTheLiveReader() throws Exception {
        assertThat(read("bytes=0-" + (BLOCK - 1)).body()).hasSize((int) BLOCK);
        assertThat(read("bytes=" + (bytes - BLOCK) + "-").body()).hasSize((int) BLOCK);
        var middle = gate(); var entered = gate();
        handler = exchange -> {
            var range = interval(exchange);
            if (range[0] == BLOCK) {
                try { exchange.getResponseHeaders().set("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + bytes);
                    exchange.sendResponseHeaders(206, range[1] - range[0] + 1); entered.countDown(); middle.await(10, TimeUnit.SECONDS); send(exchange.getResponseBody(), range[0], range[1] - range[0] + 1);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); } catch (IOException error) { throw new UncheckedIOException(error); }
            } else normal(exchange);
        };
        try (var body = client.send(get("bytes=0-"), HttpResponse.BodyHandlers.ofInputStream()).body()) {
            var prefix = body.readNBytes(64);
            byte[] expected = new byte[64]; for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i % 251);
            assertThat(prefix).containsExactly(expected);
            assertThat(middle.getCount()).isEqualTo(1);
            assertThat(body.readNBytes((int) BLOCK - 64)).hasSize((int) BLOCK - 64);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(requests).contains("GET bytes=" + BLOCK + "-" + (bytes - BLOCK - 1));
            assertThat(cache.clear().path("skipped").asInt()).isEqualTo(1);
            middle.countDown();
            var rest = body.readAllBytes(); assertThat(rest).hasSize((int) bytes - (int) BLOCK);
            for (int i = 0; i < rest.length; i++) if (rest[i] != (byte) ((BLOCK + i) % 251)) fail("Original stream differs at byte " + (BLOCK + i));
        }
        assertThat(cache.list(0, 20).path("items").get(0).path("complete").asBoolean()).isTrue();
    }

    @Test void unknownIndexedSizeIsDiscoveredByHeadAndPrefetchRegistrationDeduplicates() throws Exception {
        indexedSize = null; bytes = 2 * BLOCK;
        for (int i = 0; i < 30; i++) playback.prefetchOriginal("song", "test", () -> true);
        playback.prefetchTick(); await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("COMPLETE"));
        assertThat(requests).containsExactly("HEAD none", "GET bytes=0-" + (BLOCK - 1), "GET bytes=" + BLOCK + "-" + (bytes - 1));
        int count = requests.size(); assertThat(read("bytes=10-63").body()).hasSize(54); assertThat(requests).hasSize(count);
    }

    @Test void foregroundResponseMayRemainOpenWhileNextSongPrefetchStarts() throws Exception {
        var entered = gate(); var release = gate();
        handler = exchange -> {
            if (requests.size() == 1) {
                try { var interval = interval(exchange); exchange.getResponseHeaders().set("Content-Range", "bytes 0-" + (bytes - 1) + "/" + bytes);
                    exchange.sendResponseHeaders(206, bytes); send(exchange.getResponseBody(), 0, 65536); entered.countDown(); release.await(12, TimeUnit.SECONDS);
                    send(exchange.getResponseBody(), 65536, bytes - 65536);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); } catch (IOException error) { throw new UncheckedIOException(error); }
            } else normal(exchange);
        };
        try (var body = client.send(get(null), HttpResponse.BodyHandlers.ofInputStream()).body()) {
            assertThat(body.readNBytes(64)).hasSize(64); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            playback.prefetchOriginal("other", "live", () -> true);
            await(() -> requests.size() >= 2);
            assertThat(release.getCount()).isEqualTo(1); // The current HTTP media response has not completed.
            playback.cancelOriginalPrefetch("live"); release.countDown(); body.readAllBytes();
        }
    }

    @Test void fullManualBudgetDoesNotBlockPlaybackOrAccumulateEmptyEntries() throws Exception {
        bytes = indexedSize = 70L * 1024 * 1024;
        assertThat(read(null).body()).hasSize((int) bytes);
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) cache.list(0, 20).path("settings"); settings.put("policy", "MANUAL"); cache.save(settings);
        int entries = cache.list(0, 20).path("entryCount").asInt();
        for (int i = 0; i < 4; i++) {
            var response = new MockHttpServletResponse(); playback.play("other-" + i, "bytes=0-" + (BLOCK - 1), false, response); assertThat(response.getContentAsByteArray()).hasSize((int) BLOCK);
        }
        assertThat(cache.list(0, 20).path("entryCount").asInt()).isEqualTo(entries);
        assertThat(cache.list(0, 20).path("items").get(0).path("complete").asBoolean()).isFalse();
    }

    @Test void renamedBlockWithUncommittedByteCounterIsRecoveredOnceAndSourceChangeMisses() throws Exception {
        read("bytes=0-" + (BLOCK - 1));
        String id = cache.list(0, 20).path("items").get(0).path("id").asText();
        try (var connection = database.getConnection(); var statement = connection.prepareStatement("UPDATE song_cache_entry SET bytes=0 WHERE id=?")) { statement.setString(1, id); statement.executeUpdate(); }
        var restartedCache = new SongCacheService(mock(TranscodeClient.class), mapper, songs, json, transactions, root.resolve("cache").toString(), 64L * 1024 * 1024, 0);
        var restarted = new MediaPlaybackService(songs, sources, crypto, restartedCache, executor, json);
        var response = new MockHttpServletResponse(); restarted.play("song", "bytes=0-63", false, response);
        assertThat(((Number) mapper.find(id).get("bytes")).longValue()).isEqualTo(BLOCK); assertThat(requests).hasSize(1);
        revision = "v2"; assertThat(read("bytes=0-63").body()).hasSize(64); assertThat(requests).hasSize(2);
    }

    @Test void unknownChunkedLengthStillStreamsAndUncachedRangeZeroRetainsCompatibility() throws Exception {
        indexedSize = null; bytes = BLOCK + 123;
        handler = exchange -> {
            try { exchange.sendResponseHeaders(200, 0); send(exchange.getResponseBody(), 0, bytes); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
        assertThat(read(null).body()).hasSize((int) bytes); assertThat(cache.list(0, 20).path("entryCount").asInt()).isZero();
    }

    @Test void authorizationChecksDoNotScaleWithEverySixtyFourKilobytes() throws Exception {
        bytes = indexedSize = 12 * BLOCK; var checks = new AtomicInteger();
        var response = new MockHttpServletResponse(); playback.play("song", null, false, response, checks::incrementAndGet);
        assertThat(response.getContentAsByteArray()).hasSize((int) bytes);
        assertThat(checks.get()).isLessThan(40); // 384 reads; checks are timed or at publication boundaries.
    }

    @Test void partialCacheFromRangeIgnoringSourceStillAllowsZeroStartReplay() throws Exception {
        bytes = indexedSize = 3 * BLOCK;
        handler = exchange -> {
            try { exchange.sendResponseHeaders(200, bytes); send(exchange.getResponseBody(), 0, bytes); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
        var cancelled = new AtomicBoolean();
        var partial = new MockHttpServletResponse() {
            @Override public jakarta.servlet.ServletOutputStream getOutputStream() {
                var delegate = super.getOutputStream();
                return new jakarta.servlet.ServletOutputStream() {
                    long sent;
                    public boolean isReady() { return true; }
                    public void setWriteListener(jakarta.servlet.WriteListener listener) { }
                    public void write(int value) throws IOException { delegate.write(value); }
                    @Override public void write(byte[] data, int offset, int length) throws IOException {
                        delegate.write(data, offset, length); sent += length;
                        if (sent > BLOCK) cancelled.set(true);
                    }
                };
            }
        };
        assertThatThrownBy(() -> playback.play("song", "bytes=0-", false, partial, () -> {
            if (cancelled.get()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE, "player stopped");
        })).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(cache.list(0, 20).path("items").get(0).path("cachedBytes").asLong()).isEqualTo(BLOCK);
        var replay = read("bytes=0-"); assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.body()).hasSize((int) bytes);
        assertThat(requests).doesNotContain("GET bytes=" + BLOCK + "-" + (bytes - 1));
        assertThat(read("bytes=90-").statusCode()).isEqualTo(206); // The completed local copy now supports seek itself.
    }

    @Test void unalignedSourceReadsPreserveCompletedBlockWhenPlayerStops() throws Exception {
        bytes = indexedSize = 3 * BLOCK;
        var cancelled = new AtomicBoolean();
        Runnable guard = () -> {
            if (cancelled.get()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE, "player stopped");
        };
        var input = new FilterInputStream(new ByteArrayInputStream(new byte[(int) bytes])) {
            @Override public int read(byte[] data, int offset, int length) throws IOException {
                return super.read(data, offset, Math.min(length, 65521));
            }
        };
        var output = new OutputStream() {
            long sent;
            public void write(int value) { if (++sent > BLOCK) cancelled.set(true); }
            @Override public void write(byte[] data, int offset, int length) {
                sent += length; if (sent > BLOCK) cancelled.set(true);
            }
        };
        try (var original = cache.original("song", "unaligned-fixture", bytes, true)) {
            assertThat(original).isNotNull();
            assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(playback,
                    "tee", input, output, bytes, original, 0L, guard, false))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(original.cachedBytes()).isEqualTo(BLOCK);
        }
    }

    @Test void knownIndexedLengthDoesNotMakeUnknownChunkedZeroStartFail() throws Exception {
        bytes = BLOCK + 123; indexedSize = bytes;
        handler = exchange -> {
            try { exchange.sendResponseHeaders(200, 0); send(exchange.getResponseBody(), 0, bytes); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
        var response = read("bytes=0-"); assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).hasSize((int) bytes);
    }

    @Test void failedHitAccountingCannotLeakAReaderLease() throws Exception {
        read("bytes=0-" + (BLOCK - 1));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("optional hit accounting failed")).when(mapper).hit(anyString(), anyLong());
        assertThat(read("bytes=0-63").body()).hasSize(64);
        assertThat(cache.clear().path("skipped").asInt()).isZero();
        assertThat(cache.list(0, 20).path("entryCount").asInt()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 404})
    void stableUpstreamFailuresStopPrefetchWithoutRetrying(int status) throws Exception {
        handler = exchange -> {
            try { exchange.sendResponseHeaders(status, -1); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        };
        playback.prefetchOriginal("song", "test", () -> true); playback.prefetchTick();
        await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("FAILED"));
        assertThat(playback.originalPrefetchStatus("test").path("error").asText()).contains("已停止");
        for (int i = 0; i < 5; i++) playback.prefetchTick();
        assertThat(requests).containsExactly("GET bytes=0-" + (BLOCK - 1));
    }

    @Test void changingSourceDuringPrefetchNeverPublishesTheInFlightBlock() throws Exception {
        var entered = gate(); var release = gate();
        handler = exchange -> {
            var range = interval(exchange);
            if (range[0] != 0) {
                try {
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + bytes);
                    exchange.sendResponseHeaders(206, range[1] - range[0] + 1);
                    send(exchange.getResponseBody(), range[0], 65536); exchange.getResponseBody().flush(); entered.countDown();
                    release.await(8, TimeUnit.SECONDS);
                    send(exchange.getResponseBody(), range[0] + 65536, range[1] - range[0] + 1 - 65536);
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            } else normal(exchange);
        };
        playback.prefetchOriginal("song", "test", () -> true); playback.prefetchTick();
        assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue(); revision = "v2";
        TimeUnit.MILLISECONDS.sleep(1100); release.countDown();
        await(() -> playback.originalPrefetchStatus("test").path("state").asText().equals("FAILED"));
        assertThat(cache.list(0, 20).path("items").get(0).path("cachedBytes").asLong()).isEqualTo(BLOCK);
        assertThat(playback.originalPrefetchStatus("test").path("error").asText()).contains("已停止");
        assertThat(requests).hasSize(2);
    }
}
