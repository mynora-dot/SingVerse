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
    Path directory;
    final Connection connection = new Connection(URI.create("http://127.0.0.1:9123"), "cache-fixture-token", List.of());
    Map<String, byte[]> files;
    ObjectNode exported;
    static final long BUDGET = 64L * 1024 * 1024;

    @BeforeEach void prepare() throws Exception {
        database.update("DELETE FROM song_cache_lease");
        database.update("DELETE FROM song_cache_entry");
        database.update("UPDATE song_cache_settings SET max_bytes=0,policy='LRU',ttl_hours=168,revision=0 WHERE id=1");
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
    SongCacheService instance() { return new SongCacheService(compute, mapper, songs, json, transactions, directory.toString(), BUDGET, 0); }
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

    @Test void manualBudgetRejectsNewTasksButKeepsHitsAndTtlRemovesOnlyUnleasedEntries() throws Exception {
        String session = id();
        var stored = service.capture(row(session), "kept", connection, execution());
        service.release(session);
        String cacheId = stored.path("cacheId").asText();
        policy("MANUAL");
        try (var file = new RandomAccessFile(directory.resolve("occupied.tmp").toFile(), "rw")) { file.setLength(BUDGET); }
        assertThatThrownBy(service::assertCapacity).hasMessageContaining("507");
        String player = id();
        assertThat(service.acquire("kept", player)).isNotNull();
        Files.delete(directory.resolve("occupied.tmp"));
        policy("TTL");
        database.update("UPDATE song_cache_entry SET created_at=0");
        service.maintain();
        assertThat(service.contains(cacheId)).isTrue();
        service.release(player);
        service.maintain();
        assertThat(service.contains(cacheId)).isFalse();
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

    @Test void lruAndLfuEvictTheirOwnLowestPriorityEntryUnderCapacityPressure() throws Exception {
        for (String strategy : List.of("LRU", "LFU")) {
            String olderPopular = sparseEntry("older-popular-" + strategy, 5, 1);
            String recentUnused = sparseEntry("recent-unused-" + strategy, 0, 2);
            policy(strategy);
            Path pressure = directory.resolve("pressure.tmp");
            try (var file = new RandomAccessFile(pressure.toFile(), "rw")) { file.setLength(32L * 1024 * 1024); }
            service.assertCapacity();
            assertThat(service.contains(olderPopular)).isEqualTo(strategy.equals("LFU"));
            assertThat(service.contains(recentUnused)).isEqualTo(strategy.equals("LRU"));
            Files.delete(pressure);
            service.clear();
        }
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
