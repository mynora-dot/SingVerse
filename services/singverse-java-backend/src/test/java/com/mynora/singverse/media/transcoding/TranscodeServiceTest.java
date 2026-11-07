package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.mynora.singverse.media.service.MediaPlaybackService;
import java.net.URI;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class TranscodeServiceTest {
    @Test void autoUsesReportedLimitsWhileAnExplicitQualityStillCapsThem() {
        var fixture = new Negotiation();
        var request = fixture.request(0);
        ((ObjectNode) request.path("client")).put("max_width", 3840).put("max_height", 2160)
                .put("max_fps", 60).put("max_bitrate", 40000000);
        fixture.play(request);
        var target = fixture.started.path("target");
        assertThat(target.path("width").asInt()).isEqualTo(3840);
        assertThat(target.path("height").asInt()).isEqualTo(2160);
        assertThat(target.path("fps").asDouble()).isEqualTo(60);
        assertThat(target.path("bitrate").asInt()).isEqualTo(40000000);
        verify(fixture.compute).call(eq(fixture.connection), eq("/internal/v1/transcoding/negotiate"), eq("POST"),
                argThat(body -> body.path("client").path("max_width").asInt() == 3840
                        && !body.path("fingerprint").asText().isBlank()));
        fixture.play(request.put("quality", "1080p"));
        assertThat(fixture.started.path("target").path("width").asInt()).isEqualTo(1920);
        assertThat(fixture.started.path("target").path("fps").asDouble()).isEqualTo(30);
    }

    @Test void forcedCompatibilityEncodesTheReportedAvcProfileLevelAndKeepsTracksAndOffset() {
        var fixture = new Negotiation();
        var request = fixture.request(12.5).put("force", true).put("audio_id", "stream:2");
        ((ObjectNode) request.path("client")).putObject("profiles").putArray("h264").add("Baseline");
        ((ObjectNode) request.path("client")).putObject("levels").put("h264", 31);
        fixture.play(request);
        var target = fixture.started.path("target");
        assertThat(target.path("mode").asText()).isEqualTo("TRANSCODE");
        assertThat(target.path("profile").asText()).isEqualTo("baseline");
        assertThat(target.path("level").asInt()).isEqualTo(31);
        assertThat(target.path("width").asInt()).isEqualTo(1280);
        assertThat(target.path("height").asInt()).isEqualTo(720);
        assertThat(target.path("fps").asDouble()).isEqualTo(30);
        assertThat(target.path("audio_id").asText()).isEqualTo("stream:2");
        assertThat(target.path("start").asDouble()).isEqualTo(12.5);
        verify(fixture.mapper, never()).versions(anyString(), anyString());
    }

    @Test void explicitLowAvcLevelLimitsBitrateAndAbsentEvidenceKeepsDefaults() {
        var fixture = new Negotiation();
        var request = fixture.request(0).put("force", true);
        ((ObjectNode) request.path("client")).putObject("profiles").putArray("h264").add("Baseline");
        ((ObjectNode) request.path("client")).putObject("levels").put("h264", 13);
        fixture.play(request);
        var target = fixture.started.path("target");
        assertThat(target.path("bitrate").asInt()).isEqualTo(768000);
        int macroblocks = ((target.path("width").asInt() + 15) / 16) * ((target.path("height").asInt() + 15) / 16);
        assertThat(macroblocks).isLessThanOrEqualTo(396);
        assertThat(macroblocks * target.path("fps").asDouble()).isLessThanOrEqualTo(11880);
        ((ObjectNode) request.path("client")).put("max_width", 7680).put("max_height", 2).putObject("levels").put("h264", 10);
        fixture.play(request); target = fixture.started.path("target");
        assertThat(((target.path("width").asInt() + 15) / 16) * ((target.path("height").asInt() + 15) / 16)).isLessThanOrEqualTo(99);
        fixture.play(fixture.request(0));
        target = fixture.started.path("target");
        assertThat(target.has("profile")).isFalse(); assertThat(target.has("level")).isFalse();
        assertThat(target.path("width").asInt()).isEqualTo(1920);
        assertThat(target.path("bitrate").asInt()).isEqualTo(8000000);
    }

    @Test void readyLeaseReturnsWhileTheSchedulerIsStillCopyingTheWholeSong() throws Exception {
        var fixture = new Negotiation();
        String id = fixture.play(fixture.request(0)).path("id").asText();
        var row = fixture.rows.get(id); row.put("pipeline_version", "2");
        var remote = fixture.json.createObjectNode().put("state", "SUCCEEDED").put("ready", true);
        when(fixture.mapper.pending()).thenReturn(List.of(row));
        when(fixture.compute.call(fixture.connection, "/internal/v1/transcoding/executions/" + id, "GET", null)).thenReturn(remote);
        when(fixture.compute.call(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/lease", "POST", null)).thenReturn(remote);
        var copying = new java.util.concurrent.CountDownLatch(1); var proceed = new java.util.concurrent.CountDownLatch(1);
        when(fixture.cache.capture(eq(row), anyString(), eq(fixture.connection), any(), any())).thenAnswer(call -> {
            copying.countDown();
            if (!proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Copy was not released");
            return fixture.json.createObjectNode().put("state", "SUCCEEDED").put("ready", true).put("cacheId", "copied");
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var tick = executor.submit(fixture.service::tick);
            assertThat(copying.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var view = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                    () -> fixture.service.view(id, "admin", "/hls"));
            assertThat(view.path("status").asText()).isEqualTo("READY");
            fixture.service.tick(); // A concurrent scheduler invocation does not duplicate transfer.
            verify(fixture.cache, times(1)).capture(eq(row), anyString(), eq(fixture.connection), any(), any());
            proceed.countDown(); tick.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }

    @Test void offlineVersionsMustMatchTheMeasuredDecoderProfileAndFrameRate(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var fixture = new Negotiation(directory.toString());
        String versionId = UUID.randomUUID().toString(); Files.write(directory.resolve(versionId + ".mp4"), new byte[] {1});
        var target = fixture.json.createObjectNode().put("codec", "h264").put("width", 1920).put("height", 1080).put("bitrate", 8000000).put("fps", 30);
        var result = fixture.json.createObjectNode();
        var video = result.putObject("output").putObject("media").putArray("streams").addObject()
                .put("kind", "video").put("codec", "h264").put("profile", "High").put("level", 41).put("fps", 30).put("bit_depth", 8);
        var version = new HashMap<String, Object>(); version.put("id", versionId); version.put("target_json", target.toString()); version.put("result_json", result.toString());
        when(fixture.mapper.versions(anyString(), anyString())).thenReturn(List.of(version));
        var request = fixture.request(0);
        var client = (ObjectNode) request.path("client"); client.putArray("audio").add("aac"); client.putArray("containers").add("mp4");
        client.putObject("profiles").putArray("h264").add("Baseline"); client.putObject("levels").put("h264", 41);
        assertThat(fixture.play(request).path("mode").asText()).isEqualTo("HLS");
        video.put("profile", "Baseline").put("fps", 60); version.put("result_json", result.toString());
        assertThat(fixture.play(request).path("mode").asText()).isEqualTo("HLS");
        video.put("fps", 30).put("profile", "Constrained Baseline"); version.put("result_json", result.toString());
        assertThat(fixture.play(request).path("mode").asText()).isEqualTo("VERSION");
        client.putObject("profiles").putArray("h264").add("Constrained Baseline");
        video.put("profile", "Baseline"); version.put("result_json", result.toString());
        assertThat(fixture.play(request).path("mode").asText()).isEqualTo("HLS");
        client.putObject("profiles").putArray("h264").add("High");
        video.put("profile", "Constrained High"); version.put("result_json", result.toString());
        assertThat(fixture.play(request).path("mode").asText()).isEqualTo("VERSION");
        for (String field : List.of("fps", "bit_depth")) {
            var measured = video.remove(field); version.put("result_json", result.toString());
            assertThat(fixture.play(request).path("mode").asText()).isEqualTo("HLS");
            video.put(field, 0); version.put("result_json", result.toString());
            assertThat(fixture.play(request).path("mode").asText()).isEqualTo("HLS");
            video.set(field, measured);
        }
    }

    @Test void cancellingAnOfflineDownloadClosesItsBodyAndRemovesThePartialFile(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var fixture = new Negotiation();
        String id = fixture.play(fixture.request(0)).path("id").asText();
        var row = fixture.rows.get(id); row.put("purpose", "OFFLINE"); row.put("state", "RUNNING"); row.put("pipeline_version", "2");
        var service = new TranscodeService(fixture.mapper, fixture.compute, fixture.songs, fixture.sources,
                mock(MediaPlaybackService.class), fixture.json, directory.toString());
        ReflectionTestUtils.setField(service, "cache", fixture.cache);
        when(fixture.mapper.pending()).thenReturn(List.of(row));
        var result = fixture.json.createObjectNode().put("state", "SUCCEEDED");
        result.putObject("output").put("size", 1024).put("sha256", "unused");
        when(fixture.compute.call(fixture.connection, "/internal/v1/transcoding/executions/" + id, "GET", null)).thenReturn(result);
        var reading = new java.util.concurrent.CountDownLatch(1); var closed = new java.util.concurrent.CountDownLatch(1);
        var input = new InputStream() {
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
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(input);
        when(fixture.compute.artifact(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/artifacts/output.mp4", null)).thenReturn(response);
        // Model the SQL guard that prevents a late worker update from reviving a cancellation.
        when(fixture.mapper.update(eq(id), anyString(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            if (row.get("state").equals("CANCELLED")) return 0;
            row.put("state", call.getArgument(1)); row.put("result_json", call.getArgument(2)); return 1;
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var work = executor.submit(service::tick);
            assertThat(reading.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> service.cancel(id, "admin"));
            work.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(row).containsEntry("state", "CANCELLED");
            assertThat(closed.getCount()).isZero();
            assertThat(directory.resolve(id + ".partial")).doesNotExist();
            assertThat(directory.resolve(id + ".mp4")).doesNotExist();
            verify(fixture.compute, never()).call(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/acknowledge", "POST", null);
        } finally { input.close(); executor.shutdownNow(); }
    }

    @Test void cancellationRemainsLocalWhenTheComputeNodeIsOffline() {
        var fixture = new Negotiation();
        String id = fixture.play(fixture.request(0)).path("id").asText();
        when(fixture.compute.call(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/cancel", "POST", null))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        fixture.service.cancel(id, "admin");
        assertThat(fixture.rows.get(id)).containsEntry("state", "CANCELLED");
        verify(fixture.cache).cancelCapture(id); verify(fixture.cache).release(id);
        verify(fixture.compute, never()).call(fixture.connection, "/internal/v1/transcoding/executions/" + id, "GET", null);
    }

    @Test void cancellationDuringAnOfflineSubmissionIsRepeatedAfterRemoteAcceptance() {
        var fixture = new Negotiation();
        String id = fixture.play(fixture.request(0)).path("id").asText();
        var row = fixture.rows.get(id); row.put("purpose", "OFFLINE"); row.put("state", "QUEUED"); row.put("pipeline_version", "2");
        when(fixture.mapper.pending()).thenReturn(List.of(row));
        when(fixture.mapper.update(eq(id), anyString(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            if (row.get("state").equals("CANCELLED")) return 0;
            row.put("state", call.getArgument(1)); row.put("result_json", call.getArgument(2)); return 1;
        });
        when(fixture.compute.call(eq(fixture.connection), eq("/internal/v1/transcoding/executions"), eq("POST"), any()))
                .thenAnswer(call -> { fixture.service.cancel(id, "admin"); return fixture.json.createObjectNode().put("state", "RUNNING"); });
        fixture.service.tick();
        assertThat(row).containsEntry("state", "CANCELLED");
        verify(fixture.compute, times(2)).call(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/cancel", "POST", null);
    }

    @Test void anOfflinePublicationFailureRemovesItsPartialFile(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var fixture = new Negotiation();
        String id = fixture.play(fixture.request(0)).path("id").asText();
        var row = fixture.rows.get(id); row.put("purpose", "OFFLINE"); row.put("state", "RUNNING"); row.put("pipeline_version", "2");
        var service = new TranscodeService(fixture.mapper, fixture.compute, fixture.songs, fixture.sources,
                mock(MediaPlaybackService.class), fixture.json, directory.toString());
        ReflectionTestUtils.setField(service, "cache", fixture.cache);
        when(fixture.mapper.pending()).thenReturn(List.of(row));
        byte[] content = new byte[1024];
        var result = fixture.json.createObjectNode().put("state", "SUCCEEDED");
        result.putObject("output").put("size", content.length).put("sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)));
        when(fixture.compute.call(fixture.connection, "/internal/v1/transcoding/executions/" + id, "GET", null)).thenReturn(result);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Length", List.of("1024")), (a, b) -> true));
        when(fixture.compute.artifact(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/artifacts/output.mp4", null)).thenReturn(response);
        Files.createDirectory(directory.resolve(id + ".mp4"));
        Files.writeString(directory.resolve(id + ".mp4").resolve("keep"), "unrelated directory");
        service.tick();
        assertThat(row).containsEntry("state", "FAILED");
        assertThat(directory.resolve(id + ".partial")).doesNotExist();
        assertThat(directory.resolve(id + ".mp4").resolve("keep")).hasContent("unrelated directory");
        verify(fixture.compute, never()).call(fixture.connection, "/internal/v1/transcoding/executions/" + id + "/acknowledge", "POST", null);
    }
    @Test void seekPrefersWholeSongCacheOverExactPartialCacheAndCreatesIndependentSessions() throws Exception {
        var fixture = new Negotiation();
        var request = fixture.request(100.21576);
        String partial = fixture.seed(request);
        String whole = fixture.seed(fixture.request(0));
        fixture.clearCalls();

        var first = fixture.play(request);
        var second = fixture.play(request);

        assertThat(first.path("offset").asDouble()).isZero();
        assertThat(first.path("execution").path("cacheKey").asText()).isEqualTo(whole);
        assertThat(first.path("execution").path("cacheHit").asBoolean()).isTrue();
        assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());
        assertThat(fixture.leases).containsKeys(first.path("id").asText(), second.path("id").asText());
        assertThat(fixture.json.readTree(fixture.rows.get(first.path("id").asText()).get("target_json").toString()).path("start").asDouble()).isZero();
        assertThat(request.path("start").asDouble()).isEqualTo(100.21576);
        verify(fixture.cache, times(2)).acquire(eq(whole), anyString());
        verify(fixture.cache, never()).acquire(eq(partial), anyString());
        verify(fixture.cache, never()).assertCapacity();
        verifyNoInteractions(fixture.compute);
    }

    @Test void seekUsesExactPartialCacheOnlyAfterWholeSongMiss() throws Exception {
        var fixture = new Negotiation(); var request = fixture.request(100.21576);
        String partial = fixture.seed(request); fixture.clearCalls();

        var result = fixture.play(request);

        assertThat(result.path("offset").asDouble()).isEqualTo(100.21576);
        assertThat(result.path("execution").path("cacheKey").asText()).isEqualTo(partial);
        assertThat(result.path("execution").path("cacheHit").asBoolean()).isTrue();
        var order = inOrder(fixture.cache);
        order.verify(fixture.cache).acquire(argThat(key -> !key.equals(partial)), anyString());
        order.verify(fixture.cache).acquire(eq(partial), anyString());
        verifyNoInteractions(fixture.compute);

        fixture.clearCalls();
        var later = fixture.play(fixture.request(200.5));
        assertThat(later.path("execution").path("cacheHit").asBoolean()).isFalse();
        assertThat(fixture.started.path("target").path("start").asDouble()).isEqualTo(200.5);
        verify(fixture.cache, never()).acquire(eq(partial), anyString());
    }

    @Test void wholeSongLookupRejectsAnExecutionWithANonzeroOffsetBeforeTryingExactPartial() {
        var fixture = new Negotiation(); var request = fixture.request(100.21576);
        String partial = fixture.seed(request), whole = fixture.seed(fixture.request(0));
        fixture.entries.get(whole).put("offset", 50.0); fixture.clearCalls();

        var result = fixture.play(request);

        assertThat(result.path("offset").asDouble()).isEqualTo(100.21576);
        assertThat(result.path("execution").path("cacheKey").asText()).isEqualTo(partial);
        String session = result.path("id").asText(); var order = inOrder(fixture.cache);
        order.verify(fixture.cache).acquire(whole, session);
        order.verify(fixture.cache).release(session);
        order.verify(fixture.cache).acquire(partial, session);
        verifyNoInteractions(fixture.compute);
    }

    @Test void cacheMissPreservesRequestedSeekAndAllNegotiatedParametersForCompute() {
        var fixture = new Negotiation();
        var request = fixture.request(100.21576).put("quality", "720p").put("codec", "av1")
                .put("audio_id", "stream:1").put("subtitle_id", "stream:2").put("burn_subtitle", true).put("force", true);

        var result = fixture.play(request);

        assertThat(fixture.started.path("target").path("start").asDouble()).isEqualTo(100.21576);
        assertThat(fixture.started.path("target").path("width").asInt()).isEqualTo(1280);
        assertThat(fixture.started.path("target").path("codec").asText()).isEqualTo("av1");
        assertThat(fixture.started.path("target").path("audio_id").asText()).isEqualTo("stream:1");
        assertThat(fixture.started.path("target").path("subtitle_id").asText()).isEqualTo("stream:2");
        assertThat(fixture.started.path("target").path("burn_subtitle").asBoolean()).isTrue();
        assertThat(request.path("start").asDouble()).isEqualTo(100.21576);
        assertThat(result.path("execution").path("cacheHit").asBoolean()).isFalse();
        verify(fixture.cache).key(eq(fixture.songId), anyString(), argThat(target -> target.path("start").asDouble() == 0), eq(request.path("client")), eq(true), eq(true));
        verify(fixture.cache).key(eq(fixture.songId), anyString(), argThat(target -> target.path("start").asDouble() == 100.21576), eq(request.path("client")), eq(true), eq(true));
        verify(fixture.compute).call(eq(fixture.connection), eq("/internal/v1/transcoding/executions"), eq("POST"), any());
    }

    @Test void wholeSongLookupKeepsSongFingerprintOutputTracksClientAndIntentIsolated() {
        var fixture = new Negotiation(); String whole = fixture.seed(fixture.request(0));
        var base = fixture.request(100.21576);
        var client = base.deepCopy(); ((ObjectNode) client.path("client")).put("max_width", 1280);
        var capabilities = base.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) capabilities.path("client").path("video")).add("hevc");
        var requests = List.of(base.deepCopy().put("quality", "720p"), base.deepCopy().put("codec", "av1"),
                base.deepCopy().put("audio_id", "stream:1"), base.deepCopy().put("subtitle_id", "stream:2"),
                base.deepCopy().put("burn_subtitle", true), client, capabilities, base.deepCopy().put("force", true),
                base.deepCopy().put("codec", "h264"));
        for (var request : requests) {
            fixture.clearCalls(); var result = fixture.play(request);
            assertThat(result.path("execution").path("cacheHit").asBoolean()).as(request.toString()).isFalse();
            assertThat(fixture.started.path("target").path("start").asDouble()).isEqualTo(100.21576);
            verify(fixture.cache, never()).acquire(eq(whole), anyString());
            verify(fixture.compute).connection();
        }
        fixture.clearCalls();
        String secondSong = UUID.randomUUID().toString();
        when(fixture.songs.get(secondSong)).thenReturn(fixture.song(secondSong));
        fixture.service.negotiateMedia(secondSong, "admin", base, "/direct", "/hls");
        verify(fixture.cache, never()).acquire(eq(whole), anyString()); verify(fixture.compute).connection();

        fixture.clearCalls();
        when(fixture.sources.get(fixture.sourceId)).thenReturn(new Source(fixture.sourceId, "fixture", "LOCAL", "/fixture/media", "", "fixture-profile",
                false, "2026-11-05T00:00:00Z", "2026-11-05T01:00:00Z", false, "", null, null, "", ""));
        fixture.play(base);
        verify(fixture.cache, never()).acquire(eq(whole), anyString()); verify(fixture.compute).connection();
    }

    /** Exercise actual cache-key canonicalization with in-memory persistence and compute boundaries. */
    static class Negotiation {
        final ObjectMapper json = new ObjectMapper();
        final TranscodeMapper mapper = mock(TranscodeMapper.class);
        final TranscodeClient compute = mock(TranscodeClient.class);
        final MediaSongRepository songs = mock(MediaSongRepository.class);
        final MediaSourceRepository sources = mock(MediaSourceRepository.class);
        final SongCacheService cache = mock(SongCacheService.class);
        final String songId = UUID.randomUUID().toString(), sourceId = UUID.randomUUID().toString();
        final Connection connection = new Connection(URI.create("http://127.0.0.1:9123"), "fixture-token", List.of());
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        final Map<String, ObjectNode> entries = new HashMap<>(), leases = new HashMap<>();
        final TranscodeService service;
        ObjectNode started;
        Negotiation() { this("./unused-test-transcoding"); }
        Negotiation(String directory) {
            service = new TranscodeService(mapper, compute, songs, sources, mock(MediaPlaybackService.class), json, directory);
            ReflectionTestUtils.setField(service, "cache", cache);
            ReflectionTestUtils.setField(service, "audioJobs", mock(com.mynora.singverse.media.audio.AudioJobService.class));
            ReflectionTestUtils.setField(cache, "json", json);
            when(cache.key(anyString(), anyString(), any(), any(), anyBoolean(), anyBoolean())).thenCallRealMethod();
            when(songs.get(songId)).thenReturn(song(songId));
            when(sources.get(sourceId)).thenReturn(new Source(sourceId, "fixture", "LOCAL", "/fixture/media", "", "fixture-profile",
                    false, "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", false, "", null, null, "", ""));
            when(mapper.insert(anyMap())).thenAnswer(call -> {
                Map<String, Object> input = call.getArgument(0); Map<String, Object> row = new HashMap<>(input); String id = row.get("id").toString();
                row.put("execution_id", id); row.put("result_json", "{}"); row.put("error", ""); rows.put(id, row); return 1;
            });
            when(mapper.find(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
            when(mapper.saveActiveCache(anyString(), anyString(), anyLong())).thenAnswer(call -> {
                rows.get(call.getArgument(0)).put("result_json", call.getArgument(1)); rows.get(call.getArgument(0)).put("state", "RUNNING"); return 1;
            });
            when(mapper.update(anyString(), anyString(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
                var row = rows.get(call.getArgument(0)); row.put("state", call.getArgument(1)); row.put("result_json", call.getArgument(2)); return 1;
            });
            when(mapper.plan(anyString(), anyString())).thenAnswer(call -> { rows.get(call.getArgument(0)).put("target_json", call.getArgument(1)); return 1; });
            when(cache.acquire(anyString(), anyString())).thenAnswer(call -> {
                var entry = entries.get(call.getArgument(0)); if (entry == null) return null;
                var hit = entry.deepCopy().put("cacheHit", true); leases.put(call.getArgument(1), hit); return hit.deepCopy();
            });
            when(cache.renew(anyString())).thenAnswer(call -> { var hit = leases.get(call.getArgument(0)); return hit == null ? null : hit.deepCopy(); });
            doAnswer(call -> { leases.remove(call.getArgument(0)); return null; }).when(cache).release(anyString());
            when(compute.connection()).thenReturn(connection); when(compute.connection(connection.origin().toString())).thenReturn(connection);
            when(compute.call(eq(connection), anyString(), eq("POST"), any())).thenAnswer(call -> {
                String path = call.getArgument(1);
                if (path.endsWith("/negotiate")) return json.createObjectNode().put("mode", "TRANSCODE").set("media", json.createObjectNode());
                if (path.endsWith("/executions")) started = ((ObjectNode) call.getArgument(3)).deepCopy();
                return json.createObjectNode().put("state", "RUNNING").put("ready", false).put("offset", started.path("target").path("start").asDouble());
            });
        }
        Song song(String id) { return new Song(id, sourceId, "fixture", "song.mp4", 1L,
                "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", "fixture-profile", 1L, null, null, null, null, "fixture-content-fingerprint"); }
        ObjectNode request(double start) {
            var request = json.createObjectNode().put("start", start);
            request.putObject("client").put("hls", true).put("fmp4", true).putArray("video").add("h264").add("av1");
            return request;
        }
        ObjectNode play(ObjectNode request) { return service.negotiateMedia(songId, "admin", request, "/direct", "/hls"); }
        String seed(ObjectNode request) {
            String key = play(request).path("execution").path("cacheKey").asText();
            entries.put(key, json.createObjectNode().put("cacheId", UUID.randomUUID().toString()).put("state", "SUCCEEDED")
                    .put("ready", true).put("offset", request.path("start").asDouble()));
            return key;
        }
        void clearCalls() { clearInvocations(cache, compute); started = null; }
    }

    @Test void artifactRetriesLocalCacheWhenPublicationReleasesComputeAfterInitialMiss() throws Exception {
        var mapper = mock(TranscodeMapper.class);
        var compute = mock(TranscodeClient.class);
        var songs = mock(MediaSongRepository.class);
        var sources = mock(MediaSourceRepository.class);
        var cache = mock(SongCacheService.class);
        var service = new TranscodeService(mapper, compute, songs, sources,
                mock(MediaPlaybackService.class), new ObjectMapper(), "./unused-test-transcoding");
        ReflectionTestUtils.setField(service, "cache", cache);

        String session = UUID.randomUUID().toString();
        String songId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String origin = "http://127.0.0.1:9123";
        String filename = "master.m3u8";
        var song = new Song(songId, sourceId, "fixture", "song.mp4", 1L,
                "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", "fixture-profile", 1L,
                null, null, null, null, "fixture-content-fingerprint");
        var source = new Source(sourceId, "fixture", "LOCAL", "/fixture/media", "", "fixture-profile",
                false, "2026-11-05T00:00:00Z", "2026-11-05T00:00:00Z", false, "", null, null, "", "");
        String fingerprint = com.mynora.singverse.media.model.PlaybackFingerprint.of(song, source);
        when(songs.get(songId)).thenReturn(song);
        when(sources.get(sourceId)).thenReturn(source);
        when(mapper.find(session)).thenReturn(Map.of(
                "id", session, "execution_id", session, "song_id", songId, "owner_id", "admin",
                "purpose", "REALTIME", "state", "RUNNING", "fingerprint", fingerprint,
                "node_origin", origin, "result_json", "{}"));
        var response = new MockHttpServletResponse();

        // The cache is published and compute is acknowledged between these two local reads.
        when(cache.serve(session, filename, null, false, response)).thenReturn(false).thenAnswer(call -> {
            response.setContentType("application/vnd.apple.mpegurl");
            response.getWriter().write("#EXTM3U\nstream_v0.m3u8\n");
            return true;
        });
        var connection = new Connection(URI.create(origin), "fixture-token", List.of());
        when(compute.connection(origin)).thenReturn(connection);
        String remotePath = "/internal/v1/transcoding/executions/" + session + "/artifacts/" + filename;
        when(compute.artifact(connection, remotePath, null))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Compute output was released"));

        service.artifact(session, "admin", filename, response, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("#EXTM3U\nstream_v0.m3u8\n");
        verify(cache, times(2)).serve(session, filename, null, false, response);
        verify(compute).artifact(connection, remotePath, null);
        verify(mapper).renew(eq(session), anyLong());
    }
}
