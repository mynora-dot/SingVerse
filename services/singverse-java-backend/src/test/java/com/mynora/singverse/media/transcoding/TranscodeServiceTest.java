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
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class TranscodeServiceTest {
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
        Negotiation() {
            service = new TranscodeService(mapper, compute, songs, sources, mock(MediaPlaybackService.class), json, "./unused-test-transcoding");
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
        String fingerprint = AuthCrypto.hash(MediaSongRepository.fingerprint(song)
                + ":" + source.updatedAt() + ":" + source.location());
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
