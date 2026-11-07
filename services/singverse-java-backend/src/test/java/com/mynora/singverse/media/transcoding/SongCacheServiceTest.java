package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

/** Real isolated SQLite/XML persistence with a bounded authenticated compute download port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sqlite")
@DirtiesContext
class SongCacheServiceTest {
    @TempDir static Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> root.resolve("cache.db").toString());
        p.add("singverse.auth.key-file", () -> root.resolve("auth.key").toString());
        p.add("singverse.transcoding.cache-directory", () -> root.resolve("application-cache").toString());
        p.add("singverse.transcoding.cache-minimum-free-bytes", () -> "0");
        p.add("singverse.classification.worker-enabled", () -> "false");
        p.add("singverse.playback-activity.worker-enabled", () -> "false");
    }
    @Autowired SongCacheMapper mapper;
    @Autowired TranscodeMapper transcodes;
    @Autowired MediaSongRepository songs;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate database;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SongCacheService applicationCache;
    TranscodeClient compute;
    SongCacheService service;
    final List<SongCacheService> instances = new ArrayList<>();
    Path directory;
    final Connection connection = new Connection(URI.create("http://127.0.0.1:9123"), "cache-fixture-token", List.of());
    Map<String, byte[]> files;
    ObjectNode exported;
    static final long BUDGET = 64L * 1024 * 1024;

    @BeforeEach void prepare() throws Exception {
        database.update("DELETE FROM playback_attempt");
        database.update("DELETE FROM playback_history");
        database.update("DELETE FROM song_playback_profile");
        database.update("DELETE FROM song_cache_cleanup_job");
        database.update("UPDATE playback_activity_state SET ready=1,phase='READY' WHERE id=1");
        database.update("DELETE FROM song_cache_lease");
        database.update("DELETE FROM song_cache_entry");
        database.update("UPDATE song_cache_settings SET max_bytes=0,policy='HYBRID',ttl_hours=168,revision=0,high_watermark_percent=90,low_watermark_percent=75 WHERE id=1");
        directory = root.resolve(UUID.randomUUID().toString());
        compute = mock(TranscodeClient.class);
        service = instance();
        files = new LinkedHashMap<>();
        files.put("master.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nstream_v0.m3u8\n".getBytes(StandardCharsets.UTF_8));
        files.put("stream_v0.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nstream_v0_000000.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));
        files.put("stream_v0_000000.ts", "decoded-video-fixture".getBytes(StandardCharsets.UTF_8));
        refreshExport();
        when(compute.call(eq(connection), endsWith("/export"), eq("GET"), isNull())).thenAnswer(call -> exported);
        when(compute.artifact(eq(connection), anyString(), isNull())).thenAnswer(call -> {
            String path = call.getArgument(1); String name = path.substring(path.lastIndexOf('/') + 1);
            var response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(Integer.toString(files.get(name).length))), (a, b) -> true));
            when(response.body()).thenReturn(new ByteArrayInputStream(files.get(name)));
            return response;
        });
    }
    @AfterEach void closeInstances() { instances.forEach(SongCacheService::close); }
    SongCacheService instance() { var value = new SongCacheService(compute, mapper, songs, json, transactions, directory.toString(), BUDGET, 0); instances.add(value); return value; }
    void refreshExport() throws Exception {
        exported = json.createObjectNode(); var artifacts = exported.putObject("artifacts");
        for (var entry : files.entrySet()) artifacts.putObject(entry.getKey()).put("size", entry.getValue().length)
                .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue())));
    }
    Map<String, Object> row(String session) { return Map.of("id", session, "execution_id", session, "song_id", "removed-song", "target_json", "{\"codec\":\"h264\",\"mode\":\"TRANSCODE\"}"); }
    ObjectNode execution() throws Exception { return (ObjectNode) json.readTree("{\"state\":\"SUCCEEDED\",\"ready\":true,\"trackMapping\":[{\"kind\":\"video\"}],\"media\":{\"duration\":4},\"reasons\":[\"VIDEO_CODEC\"]}"); }
    String id() { return UUID.randomUUID().toString(); }
    void policy(String policy) { var settings = (ObjectNode) service.list(0, 20).path("settings"); settings.put("policy", policy); service.save(settings); }

    @Test void durableLocalCacheSupportsIndependentLeasesRestartAndOfflineRanges() throws Exception {
        String producer = id(), second = id();
        String key = service.key("removed-song", "f", json.createObjectNode().put("codec", "h264"), json.createObjectNode(), false, false);
        var stored = service.capture(row(producer), key, connection, execution());
        String cacheId = stored.path("cacheId").asText();
        assertThat(stored.path("cacheHit").asBoolean()).isFalse();
        assertThat(stored.path("media").path("duration").asInt()).isEqualTo(4);
        assertThat(service.contains(cacheId)).isTrue();
        verify(compute, times(3)).artifact(eq(connection), anyString(), isNull());
        reset(compute);
        var hit = service.acquire(key, second);
        assertThat(hit.path("cacheId").asText()).isEqualTo(cacheId);
        assertThat(hit.path("cacheHit").asBoolean()).isTrue();
        assertThat(service.list(0, 20).path("items").get(0).path("hits").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> service.delete(cacheId)).hasMessageContaining("409");
        service.release(producer);
        assertThatThrownBy(() -> service.delete(cacheId)).hasMessageContaining("409");
        var partial = new MockHttpServletResponse();
        assertThat(service.serve(second, "stream_v0_000000.ts", "bytes=2-7", false, partial)).isTrue();
        assertThat(partial.getStatus()).isEqualTo(206);
        assertThat(partial.getContentAsByteArray()).isEqualTo(Arrays.copyOfRange(files.get("stream_v0_000000.ts"), 2, 8));
        var head = new MockHttpServletResponse();
        assertThat(service.serve(second, "stream_v0_000000.ts", null, true, head)).isTrue();
        assertThat(head.getContentAsByteArray()).isEmpty();
        assertThat(head.getContentLengthLong()).isEqualTo(files.get("stream_v0_000000.ts").length);
        var settings = (ObjectNode) service.list(0, 20).path("settings");
        settings.put("policy", "LFU");
        var saved = service.save(settings);
        assertThatThrownBy(() -> service.save(settings)).hasMessageContaining("409");
        service.release(second);
        service = instance();
        assertThat(service.list(0, 20).path("settings")).isEqualTo(saved);
        assertThat(service.acquire(key, id()).path("cacheHit").asBoolean()).isTrue();
        assertThat(service.clear().path("skipped").asInt()).isEqualTo(1);
        database.update("UPDATE song_cache_lease SET renewed_at=0");
        service.delete(cacheId);
        assertThat(service.list(0, 20).path("entryCount").asInt()).isZero();
        verifyNoInteractions(compute);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1000, 19998})
    void hotReadsDoNotValidateUnrelatedSegmentsButInspectionAndRestartDo(int segmentCount) throws Exception {
        String cacheId = id(), key = "many-segments-" + segmentCount, player = id();
        Path folder = Files.createDirectories(directory.resolve(cacheId));
        Files.writeString(folder.resolve(".singverse-cache-owner"), "SingVerse song cache v1\n");
        var artifacts = json.createObjectNode();
        for (String name : List.of("master.m3u8", "stream_v0.m3u8")) {
            Files.writeString(folder.resolve(name), "#EXTM3U\n");
            artifacts.putObject(name).put("size", Files.size(folder.resolve(name)));
        }
        for (int index = 0; index < segmentCount; index++) {
            String name = "stream_v0_" + index + ".ts";
            Files.write(folder.resolve(name), new byte[]{42});
            artifacts.putObject(name).put("size", 1);
        }
        mapper.insert(Map.of("id", cacheId, "cache_key", key, "song_id", "removed-song", "execution_json", execution().toString(),
                "target_json", "{}", "artifacts_json", artifacts.toString(), "media_type", "VIDEO", "bytes", segmentCount + 16L, "created_at", 1L));
        assertThat(service.acquire(key, player)).isNotNull();
        Files.delete(folder.resolve("stream_v0_" + (segmentCount - 1) + ".ts"));
        assertThat(service.contains(cacheId)).isTrue();
        assertThat(service.renew(player)).isNotNull();
        var response = new MockHttpServletResponse();
        assertThat(service.serve(player, "stream_v0_0.ts", null, false, response)).isTrue();
        assertThat(response.getContentAsByteArray()).containsExactly((byte) 42);
        assertThat(instance().acquire(key, id())).isNull();
        service.maintain(); service.awaitMaintenance();
        assertThat(service.contains(cacheId)).isFalse();
        assertThat(service.renew(player)).isNull();
    }

    @Test void currentFileLengthAndNoFollowChecksRemainEffectiveForWarmCache() throws Exception {
        String player = id();
        String cacheId = service.capture(row(player), "protected-segment", connection, execution()).path("cacheId").asText();
        Path current = directory.resolve(cacheId).resolve("stream_v0_000000.ts");
        Files.writeString(current, "unexpected length");
        assertThatThrownBy(() -> service.serve(player, "stream_v0_000000.ts", null, false, new MockHttpServletResponse())).hasMessageContaining("410");
        Files.delete(current);
        Path outside = root.resolve("outside-" + id());
        Files.write(outside, files.get("stream_v0_000000.ts"));
        Files.createSymbolicLink(current, outside);
        assertThatThrownBy(() -> service.serve(player, "stream_v0_000000.ts", null, true, new MockHttpServletResponse())).hasMessageContaining("410");
        Files.delete(directory.resolve(cacheId).resolve(".singverse-cache-owner"));
        assertThat(service.renew(player)).isNull();
    }

    @Test void capacityEvictionUsesTheLowWatermarkAndNoRepeatedFullRegistryReads() throws Exception {
        for (int index = 0; index < 4; index++) sparseEntry("eviction-" + index, 0, index);
        service = instance(); // External fixture writes precede the production ledger snapshot.
        service.assertCapacity(); service.awaitMaintenance();
        assertThat(mapper.entries()).hasSize(1);
        assertThat(service.list(0, 20).path("usageBytes").asLong()).isLessThanOrEqualTo(BUDGET * 75 / 100);
    }

    @Test void rejectsWrongDigestAndExternalPlaylistReferencesBeforePublication() throws Exception {
        exported.withObject("artifacts").withObject("stream_v0_000000.ts").put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> service.capture(row(id()), "bad-digest", connection, execution())).hasMessageContaining("摘要");
        assertThat(mapper.entries()).isEmpty();
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
        files.put("stream_v0.m3u8", "#EXTM3U\n#EXTINF:4,\n../outside.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8));
        refreshExport();
        assertThatThrownBy(() -> service.capture(row(id()), "bad-playlist", connection, execution())).hasMessageContaining("清单外引用");
        assertThat(mapper.entries()).isEmpty();
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stalledOrCancelledDownloadsCloseBodiesAndReleaseTheirCapacity(boolean cancel) throws Exception {
        String session = id();
        var reading = new java.util.concurrent.CountDownLatch(1);
        var closed = new java.util.concurrent.CountDownLatch(1);
        var blocked = new InputStream() {
            @Override public int read() throws IOException { return read(new byte[1], 0, 1); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                reading.countDown();
                try { if (!closed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Body was not closed"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                throw new IOException("Body closed");
            }
            @Override public void close() { closed.countDown(); }
        };
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(Integer.toString(files.get("stream_v0_000000.ts").length))), (a, b) -> true));
        when(response.body()).thenAnswer(call -> new ProgressTimeoutInputStream(blocked, java.time.Duration.ofMillis(cancel ? 5000 : 100)));
        when(compute.artifact(eq(connection), endsWith("/stream_v0_000000.ts"), isNull())).thenReturn(response);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var work = executor.submit(() -> service.capture(row(session), "interrupted-" + cancel, connection, execution()));
            assertThat(reading.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.committedUsage()).isPositive();
            if (cancel) org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> service.cancelCapture(session));
            assertThatThrownBy(() -> work.get(3, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(closed.getCount()).isZero();
            assertThat(service.committedUsage()).isZero();
            assertThat(mapper.entries()).isEmpty();
            try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
            service.assertCapacity();
        } finally { service.cancelCapture(session); blocked.close(); executor.shutdownNow(); }
    }

    @Test void aSessionEndingAfterTheLastByteCannotPublishOrBindTheCache() throws Exception {
        String session = id(); var active = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(compute.artifact(eq(connection), endsWith("/stream_v0_000000.ts"), isNull())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(Integer.toString(files.get("stream_v0_000000.ts").length))), (a, b) -> true));
            when(response.body()).thenReturn(new ByteArrayInputStream(files.get("stream_v0_000000.ts")) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) {
                    int count = super.read(bytes, offset, length); if (count < 0) active.set(false); return count;
                }
            });
            return response;
        });
        assertThatThrownBy(() -> service.capture(row(session), "ended-session", connection, execution(), active::get)).hasMessageContaining("410");
        assertThat(service.committedUsage()).isZero();
        assertThat(mapper.entries()).isEmpty();
        assertThat(mapper.lease(session)).isNull();
        try (var paths = Files.list(directory)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test void canonicalCacheKeyIncludesFingerprintAllOutputParametersAndPlaybackIntent() throws Exception {
        var target = json.readTree("{\"width\":1920,\"codec\":\"h264\",\"audio_id\":\"stream:1\"}");
        var reordered = json.readTree("{\"audio_id\":\"stream:1\",\"codec\":\"h264\",\"width\":1920}");
        var client = json.readTree("{\"audio\":[\"aac\"],\"fmp4\":true}");
        String key = service.key("song", "f", target, client, false, false);
        assertThat(service.key("song", "f", reordered, client, false, false)).isEqualTo(key);
        assertThat(service.key("song", "changed", target, client, false, false)).isNotEqualTo(key);
        assertThat(service.key("song", "f", target, client, true, false)).isNotEqualTo(key);
        assertThat(service.key("song", "f", target, client, false, true)).isNotEqualTo(key);
        ((ObjectNode) reordered).put("audio_id", "stream:2");
        assertThat(service.key("song", "f", reordered, client, false, false)).isNotEqualTo(key);
        assertThat(service.acquire("absent", id())).isNull();
        verifyNoInteractions(compute);
    }

    @Test void manualBudgetNeverBlocksPlaybackAndLegacyTtlNoLongerExpiresByCreationTime() throws Exception {
        String session = id();
        var stored = service.capture(row(session), "kept", connection, execution());
        service.release(session); String cacheId = stored.path("cacheId").asText();
        policy("MANUAL");
        try (var file = new RandomAccessFile(directory.resolve("occupied.tmp").toFile(), "rw")) { file.setLength(BUDGET); }
        service.startMaintenance(); service.awaitMaintenance();
        assertThatCode(service::assertCapacity).doesNotThrowAnyException();
        String player = id(); assertThat(service.acquire("kept", player)).isNotNull();
        Files.delete(directory.resolve("occupied.tmp"));
        policy("TTL"); database.update("UPDATE song_cache_entry SET created_at=0");
        service.maintain(); service.awaitMaintenance(); assertThat(service.contains(cacheId)).isTrue();
        service.release(player);
        assertThat(service.list(0,20).path("settings").path("policy").asText()).isEqualTo("HYBRID");
        assertThat(service.clear().path("removed").asInt()).isEqualTo(1);
    }

    @Test void validatesPaginationAndSettingsBeforeMutatingPersistentPolicy() {
        assertThatThrownBy(() -> service.list(-1, 20)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.list(0, 101)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.save(json.createObjectNode().put("policy", "LRU"))).hasMessageContaining("400");
        var config = (ObjectNode) service.list(0, 20).path("settings"); config.put("maxBytes", 1);
        assertThatThrownBy(() -> service.save(config)).hasMessageContaining("400");
        assertThat(service.list(0, 20).path("settings").path("revision").asInt()).isZero();
        verifyNoInteractions(compute);
    }

    @Test void oldAutomaticPolicyWritesNormalizeAndIgnoreLifetimeHttpHits() throws Exception {
        for (String strategy : List.of("LRU", "LFU", "TTL")) {
            policy(strategy); service.awaitMaintenance();
            assertThat(service.list(0,20).path("settings").path("policy").asText()).isEqualTo("HYBRID");
        }
        String ancient = sparseEntry("ancient-popular", 1_000_000, 1);
        var listed = service.list(0,20).path("items").get(0);
        assertThat(listed.path("H").asDouble()).isZero(); assertThat(listed.path("F").asDouble()).isZero();
        assertThat(listed.path("priority").asDouble()).isZero(); assertThat(listed.path("lifecycle").asText()).isEqualTo("EVICT");
        assertThat(service.clear().path("removed").asInt()).isEqualTo(1);
        assertThat(mapper.find(ancient)).isNull();
    }

    String sparseEntry(String key, int hits, long access) throws Exception {
        String id = id(); Path folder = Files.createDirectories(directory.resolve(id));
        Files.writeString(folder.resolve(".singverse-cache-owner"), "SingVerse song cache v1\n");
        var artifacts = json.createObjectNode();
        for (var file : files.entrySet()) {
            Files.write(folder.resolve(file.getKey()), file.getValue());
            artifacts.putObject(file.getKey()).put("size", file.getValue().length).put("sha256", "0".repeat(64));
        }
        long bytes = 24L * 1024 * 1024;
        try (var file = new RandomAccessFile(folder.resolve("stream_v0_000000.ts").toFile(), "rw")) { file.setLength(bytes); }
        artifacts.withObject("stream_v0_000000.ts").put("size", bytes);
        mapper.insert(Map.of("id", id, "cache_key", key, "song_id", "removed-song", "execution_json", execution().toString(),
                "target_json", "{}", "artifacts_json", artifacts.toString(), "media_type", "VIDEO", "bytes", bytes, "created_at", access));
        database.update("UPDATE song_cache_entry SET hits=?,last_access_at=? WHERE id=?", hits, access, id);
        return id;
    }

    static final long NOW = java.time.Instant.parse("2026-11-07T00:00:00Z").toEpochMilli();
    static final long DAY = CacheEvictionPolicy.DAY;
    String seed(String song, long bytes) throws Exception {
        String cacheId = id(); Path folder = Files.createDirectories(directory.resolve(cacheId));
        Files.writeString(folder.resolve(".singverse-cache-owner"), "SingVerse song cache v1\n");
        try (var file = new RandomAccessFile(folder.resolve("original_0.block").toFile(), "rw")) { file.setLength(bytes); }
        mapper.ensureProfile(song);
        mapper.insert(Map.of("id", cacheId, "cache_key", "seed-" + cacheId, "song_id", song, "execution_json", "{}",
                "target_json", "{\"mode\":\"ORIGINAL\",\"sourceBytes\":" + bytes + "}", "artifacts_json", "{}", "media_type", "AUDIO",
                "bytes", bytes, "created_at", NOW - 70 * DAY));
        return cacheId;
    }
    void activity(String song, double heat, long age) {
        mapper.ensureProfile(song);
        database.update("UPDATE song_playback_profile SET last_played_at=?,heat=?,heat_updated_at=? WHERE song_id=?", NOW-age, heat, NOW-age, song);
    }
    void fixedService() { service = instance(); service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneOffset.UTC)); }

    @Test void highAndLowHeatRecentSongsAreProtectedWhileAncientPopularSongsAreCandidates() throws Exception {
        String hot = seed("hot", 1024), fresh = seed("fresh", 1024), old = seed("old-popular", 1024), unused = seed("unused", 1024);
        activity("hot", 1, 1000); activity("fresh", .05, 1000); activity("old-popular", 1, 61 * DAY); activity("unused", .05, 61 * DAY);
        fixedService();
        var ranked = SongCacheService.rankCandidates(mapper.candidates(NOW - 7 * DAY, NOW), NOW);
        assertThat(ranked.get(0).get("id")).isEqualTo(unused);
        assertThat(ranked.get(1).get("id")).isEqualTo(old);
        var result = service.clear();
        assertThat(result.path("removed").asInt()).isEqualTo(2);
        assertThat(result.path("skippedByReason").path("RECENT_PLAY").asInt()).isEqualTo(2);
        assertThat(mapper.find(hot)).isNotNull(); assertThat(mapper.find(fresh)).isNotNull();
        assertThat(mapper.find(old)).isNull(); assertThat(mapper.find(unused)).isNull();
    }

    @Test void songPinsProtectAllVariantsAndUseAnIndependentRevision() throws Exception {
        String first = seed("pinned", 1024), second = seed("pinned", 1024);
        fixedService();
        var request = json.createObjectNode().put("pinned", true).put("revision", 0);
        assertThat(service.pin("pinned", request).path("revision").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> service.pin("pinned", request)).hasMessageContaining("409");
        activity("pinned", 1, 61 * DAY);
        String future = seed("pinned", 1024);
        var result = service.clear();
        assertThat(result.path("removed").asInt()).isZero(); assertThat(result.path("skipped").asInt()).isEqualTo(3);
        assertThat(service.pins(0, 20).path("items").get(0).path("revision").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> service.delete(first, true)).hasMessageContaining("409");
        service.pin("pinned", request.put("pinned", false).put("revision", 1));
        assertThat(service.clear().path("removed").asInt()).isEqualTo(3);
        assertThat(mapper.find(second)).isNull(); assertThat(mapper.find(future)).isNull();
    }

    @Test void recentOverrideIsOnlyForAnExplicitSingleDeleteAndCannotOverridePlayback() throws Exception {
        String entry = seed("recent", 1024); activity("recent", .05, 1000); fixedService();
        assertThatThrownBy(() -> service.delete(entry)).hasMessageContaining("409");
        mapper.bind(id(), entry, NOW);
        assertThatThrownBy(() -> service.delete(entry, true)).hasMessageContaining("409");
        database.update("DELETE FROM song_cache_lease");
        service.delete(entry, true); assertThat(mapper.find(entry)).isNull();
    }

    @Test void completedNextSongPrefetchRemainsProtectedUntilTheQueueReleasesIt() throws Exception {
        String entry = seed("next", 1024); fixedService(); service.protectPrefetch("queue", "next");
        var result = service.clear();
        assertThat(result.path("skippedByReason").path("PREFETCH").asInt()).isEqualTo(1);
        assertThat(mapper.find(entry)).isNotNull();
        service.protectPrefetch("queue", null);
        assertThat(service.clear().path("removed").asInt()).isEqualTo(1);
    }

    @Test void exactHighWatermarkTriggersAndStopsAfterReachingLowWatermark() throws Exception {
        long owner = "SingVerse song cache v1\n".length();
        String entry = seed("threshold", BUDGET * 90 / 100 - owner - 1); fixedService();
        service.maintain(); service.awaitMaintenance(); assertThat(mapper.find(entry)).isNotNull();
        database.update("UPDATE song_cache_entry SET bytes=bytes+1 WHERE id=?", entry);
        fixedService(); service.maintain(); service.awaitMaintenance(); assertThat(mapper.find(entry)).isNull();
        for (int index = 0; index < 3; index++) seed("watermark-" + index, 20L * 1024 * 1024 - owner);
        fixedService(); service.maintain(); service.awaitMaintenance();
        assertThat(mapper.entries()).hasSize(2);
        assertThat(service.committedUsage()).isEqualTo(40L * 1024 * 1024);
    }

    @Test void protectedPressureFinishesWithoutBreakingProtectionsAndReportsBothTargets() throws Exception {
        String entry = seed("protected", BUDGET); fixedService(); service.pin("protected", json.createObjectNode().put("pinned", true).put("revision", 0));
        service.awaitMaintenance();
        var submitted = service.cleanupJob(json.createObjectNode().put("mode", "POLICY")); service.awaitMaintenance();
        var result = service.job(submitted.path("id").asText());
        assertThat(result.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("targetReached").asBoolean()).isFalse(); assertThat(result.path("spaceInsufficient").asBoolean()).isTrue();
        assertThat(result.path("shortfallBytes").asLong()).isPositive(); assertThat(mapper.find(entry)).isNotNull();
    }

    @Test void pendingStatisticsPreventUninformedAutomaticAndBatchEviction() throws Exception {
        String entry = seed("backfill", BUDGET); fixedService();
        database.update("UPDATE playback_activity_state SET ready=0,phase='PREPARE' WHERE id=1");
        var result = service.clear(); assertThat(result.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(result.path("skippedByReason").path("STATISTICS_NOT_READY").asInt()).isEqualTo(1);
        assertThat(mapper.find(entry)).isNotNull();
        database.update("UPDATE playback_activity_state SET ready=1,phase='READY' WHERE id=1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3})
    void restartRecoversEveryDeletionStageWithoutLosingTheIndexPrematurely(int stage) throws Exception {
        String entry = seed("restart", 1024), token = id();
        mapper.claimDelete(entry, token);
        Path original = directory.resolve(entry), quarantine = directory.resolve(".deleting-" + entry + "-" + token);
        if (stage >= 1) Files.move(original, quarantine);
        if (stage >= 2) Files.delete(quarantine.resolve("original_0.block"));
        if (stage >= 3) { Files.delete(quarantine.resolve(".singverse-cache-owner")); Files.delete(quarantine); }
        fixedService(); service.startMaintenance(); service.awaitMaintenance();
        assertThat(mapper.find(entry)).isNull(); assertThat(original).doesNotExist(); assertThat(quarantine).doesNotExist();
        assertThat(service.committedUsage()).isZero();
    }

    @Test void deletionFailureRetainsJournalAndRetryRepairsFilesAndIndex() throws Exception {
        String entry = seed("retry", 1024); fixedService();
        Path marker = directory.resolve(entry).resolve(".singverse-cache-owner"); Files.delete(marker);
        var result = service.clear();
        assertThat(result.path("failed").asInt()).isEqualTo(1); assertThat(mapper.find(entry)).containsEntry("state", "DELETING");
        assertThat(service.committedUsage()).isPositive();
        assertThat(service.pin("other", json.createObjectNode().put("pinned", true).put("revision", 0))).isNotNull();
        assertThatThrownBy(() -> service.pin("retry", json.createObjectNode().put("pinned", true).put("revision", 0))).hasMessageContaining("409");
        Files.writeString(marker, "SingVerse song cache v1\n");
        service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW + 60_001), java.time.ZoneOffset.UTC));
        service.maintain(); service.awaitMaintenance();
        assertThat(mapper.find(entry)).isNull(); assertThat(directory.resolve(entry)).doesNotExist(); assertThat(service.committedUsage()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PLAYING", "PAUSED", "BUFFERING"})
    void songLevelPlaybackProtectsAfterTheOriginalHttpResponseHasClosed(String state) throws Exception {
        String entry = seed("occupied", 1024); fixedService();
        database.update("INSERT INTO playback_attempt(id,client_type,owner_session_id,song_id,snapshot_json,state,started_at,started_playing_at,last_event_at,lease_until) VALUES(?,'MOBILE','owner','occupied','{}',?,?,?,?,?)",
                id(), state, NOW - 200_000, NOW - 200_000, NOW, NOW + 120_000);
        assertThat(service.clear().path("skippedByReason").path("PLAYING").asInt()).isEqualTo(1);
        assertThatThrownBy(() -> service.delete(entry, true)).hasMessageContaining("409");
        service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW + 120_000), java.time.ZoneOffset.UTC));
        assertThat(service.clear().path("removed").asInt()).isEqualTo(1);
    }

    @Test void clearCoalescesAndCanBeCancelledBeforeClaimingFiles() throws Exception {
        String entry = seed("cancel", 1024);
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var controlled = spy(mapper);
        doAnswer(call -> {
            var rows = mapper.candidates(call.getArgument(0), call.getArgument(1)); entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return rows;
        }).when(controlled).candidates(anyLong(), anyLong());
        service = new SongCacheService(compute, controlled, songs, json, transactions, directory.toString(), BUDGET, 0); instances.add(service);
        service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneOffset.UTC));
        var first = service.cleanupJob(json.createObjectNode().put("mode", "CLEAR"));
        try {
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var duplicate = service.cleanupJob(json.createObjectNode().put("mode", "CLEAR"));
            assertThat(duplicate.path("id")).isEqualTo(first.path("id"));
            assertThat(service.cancelJob(first.path("id").asText()).path("state").asText()).isEqualTo("CANCELLING");
        } finally { release.countDown(); }
        service.awaitMaintenance(); assertThat(service.job(first.path("id").asText()).path("state").asText()).isEqualTo("CANCELLED");
        assertThat(mapper.find(entry)).isNotNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aPlayOrPinArrivingAfterCandidateSelectionIsRecheckedInsideTheClaim(boolean pin) throws Exception {
        String entry = seed("racing", 1024);
        var selected = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var controlled = spy(mapper);
        doAnswer(call -> {
            var rows = mapper.candidates(call.getArgument(0), call.getArgument(1)); selected.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return rows;
        }).when(controlled).candidates(anyLong(), anyLong());
        service = new SongCacheService(compute, controlled, songs, json, transactions, directory.toString(), BUDGET, 0); instances.add(service);
        service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneOffset.UTC));
        var job = service.cleanupJob(json.createObjectNode().put("mode", "CLEAR"));
        try {
            assertThat(selected.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            if (pin) service.pin("racing", json.createObjectNode().put("pinned", true).put("revision", 0));
            else mapper.bind(id(), entry, NOW);
        } finally { release.countDown(); }
        service.awaitMaintenance(); assertThat(mapper.find(entry)).containsEntry("state", "ACTIVE");
        assertThat(service.job(job.path("id").asText()).path("removed").asInt()).isZero();
    }

    @Test void failedDatabaseFinalizationKeepsDeletingIndexUntilRecovery() throws Exception {
        String entry = seed("commit", 1024); var controlled = spy(mapper);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture commit failure")).when(controlled).delete(entry);
        service = new SongCacheService(compute, controlled, songs, json, transactions, directory.toString(), BUDGET, 0); instances.add(service);
        service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW), java.time.ZoneOffset.UTC));
        assertThat(service.clear().path("failed").asInt()).isEqualTo(1);
        assertThat(mapper.find(entry)).containsEntry("state", "DELETING"); assertThat(directory.resolve(entry)).doesNotExist();
        assertThat(service.committedUsage()).isPositive();
        fixedService(); service.clock(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(NOW + 60_001), java.time.ZoneOffset.UTC));
        service.startMaintenance(); service.awaitMaintenance();
        assertThat(mapper.find(entry)).isNull(); assertThat(service.committedUsage()).isZero();
    }

    @Test void anInFlightDownloadProtectsOtherCachedVariantsOfTheSameSong() throws Exception {
        String entry = seed("removed-song", 1024); fixedService(); String session = id();
        var reading = new java.util.concurrent.CountDownLatch(1); var closed = new java.util.concurrent.CountDownLatch(1);
        var body = new InputStream() {
            @Override public int read() throws IOException { return read(new byte[1], 0, 1); }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                reading.countDown();
                try { if (!closed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new IOException("fixture closed");
            }
            @Override public void close() { closed.countDown(); }
        };
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of(Integer.toString(files.get("stream_v0_000000.ts").length))), (a,b) -> true)); when(response.body()).thenReturn(body);
        when(compute.artifact(eq(connection), endsWith("/stream_v0_000000.ts"), isNull())).thenReturn(response);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var downloading = executor.submit(() -> service.capture(row(session), "download-variant", connection, execution()));
            assertThat(reading.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var result = service.clear();
            assertThat(result.path("skippedByReason").path("DOWNLOADING").asInt()).isEqualTo(1);
            assertThat(mapper.find(entry)).containsEntry("state", "ACTIVE");
            service.cancelCapture(session);
            assertThatThrownBy(() -> downloading.get(3, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(service.clear().path("removed").asInt()).isEqualTo(1);
        } finally { service.cancelCapture(session); body.close(); executor.shutdownNow(); }
    }

    @Test void orphanRecoveryRemovesOnlyDirectoriesWithVerifiedOwnershipMarkers() throws Exception {
        service.assertCapacity();
        Path owned = Files.createDirectory(directory.resolve(id()));
        Files.writeString(owned.resolve(".singverse-cache-owner"), "SingVerse song cache v1\n");
        Files.writeString(owned.resolve("segment.tmp"), "interrupted publish");
        Path userFolder = Files.createDirectory(directory.resolve(id()));
        Files.writeString(userFolder.resolve("personal.mp4"), "user-owned");
        Path fake = Files.createDirectory(directory.resolve(".partial-" + id()));
        Files.writeString(fake.resolve(".singverse-cache-owner"), "unrelated application");
        service.clear();
        assertThat(owned).doesNotExist();
        assertThat(userFolder.resolve("personal.mp4")).hasContent("user-owned");
        assertThat(fake).exists();
    }

    @Test void conditionalRealtimeExpiryIgnoresStalePendingSnapshotsAndCannotReviveCancelledRows() {
        String session = id();
        var row = new HashMap<String, Object>(row(session));
        row.put("owner_id", "admin"); row.put("purpose", "REALTIME"); row.put("state", "RUNNING");
        row.put("fingerprint", "fixture"); row.put("node_origin", "http://127.0.0.1:9123");
        row.put("input_json", "{}"); row.put("now", 1000L);
        transcodes.insert(row);
        String retained = "{\"cacheId\":\"published-cache\",\"cacheHit\":true}";
        transcodes.update(session, "RUNNING", retained, "", 1000);
        try {
            var oldPending = transcodes.pending().stream().filter(item -> session.equals(item.get("id"))).findFirst().orElseThrow();
            assertThat(((Number) oldPending.get("renewed_at")).longValue()).isEqualTo(1000);
            transcodes.renew(session, 200_000);
            assertThat(transcodes.expireRealtime(session, 100_000, 220_000)).isZero();
            assertThat(transcodes.find(session)).containsEntry("state", "RUNNING").containsEntry("result_json", retained);
            assertThat(transcodes.expireRealtime(session, 200_000, 330_000)).isEqualTo(1);
            var expired = transcodes.find(session);
            assertThat(expired).containsEntry("state", "CANCELLED").containsEntry("result_json", retained);
            assertThat(((Number) expired.get("updated_at")).longValue()).isEqualTo(330_000);
            assertThat(transcodes.expireRealtime(session, Long.MAX_VALUE, 340_000)).isZero();
            assertThat(transcodes.update(session, "RUNNING", "{}", "", 340_000)).isZero();
            assertThat(transcodes.requeue(session, id(), 340_000)).isZero();
            assertThat(transcodes.find(session)).containsEntry("state", "CANCELLED").containsEntry("result_json", retained);
        } finally { transcodes.delete(session); }

        String offline = id(); row.put("id", offline); row.put("purpose", "OFFLINE");
        transcodes.insert(row);
        try { assertThat(transcodes.expireRealtime(offline, Long.MAX_VALUE, 400_000)).isZero(); }
        finally { transcodes.delete(offline); }
    }

    @Test void cachePublicationCannotReviveFailedOrCancelledPlaybackRows() {
        String previous = "{\"state\":\"previous\"}", published = "{\"cacheId\":\"published-cache\"}";
        for (String state : List.of("STARTING", "RUNNING", "FAILED", "CANCELLED")) {
            String session = id();
            var row = new HashMap<String, Object>(row(session));
            row.put("owner_id", "admin"); row.put("purpose", "REALTIME"); row.put("state", "RUNNING");
            row.put("fingerprint", "fixture"); row.put("node_origin", "http://127.0.0.1:9123");
            row.put("input_json", "{}"); row.put("now", 1000L);
            transcodes.insert(row);
            transcodes.update(session, state, previous, "prior-error", 2000);
            try {
                boolean active = Set.of("STARTING", "RUNNING").contains(state);
                assertThat(transcodes.saveActiveCache(session, published, 3000)).isEqualTo(active ? 1 : 0);
                assertThat(transcodes.find(session))
                        .containsEntry("state", active ? "RUNNING" : state)
                        .containsEntry("result_json", active ? published : previous)
                        .containsEntry("error", active ? "" : "prior-error");
            } finally { transcodes.delete(session); }
        }
    }
}
