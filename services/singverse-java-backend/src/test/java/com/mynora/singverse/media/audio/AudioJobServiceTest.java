package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.io.*;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AudioJobServiceTest {
    @TempDir Path root;
    final AudioRoleMapper mapper=mock(AudioRoleMapper.class);
    final MediaSongRepository songs=mock(MediaSongRepository.class);
    final TranscodeClient compute=mock(TranscodeClient.class);
    final ObjectMapper json=new ObjectMapper();
    final Connection connection=mock(Connection.class);
    final String id=UUID.randomUUID().toString();
    AudioJobService service(String fingerprint) {
        var song=mock(Song.class);when(song.contentFingerprint()).thenReturn(fingerprint);when(songs.get("song")).thenReturn(song);
        when(compute.connection("node")).thenReturn(connection);
        when(mapper.pending()).thenReturn(List.of(Map.of("id",id,"song_id","song","kind","SEPARATE","state","RUNNING","fingerprint","current","node_origin","node","result_json","{}")));
        return new AudioJobService(mapper,mock(AudioRoleService.class),songs,mock(MediaSourceRepository.class),mock(MediaPlaybackService.class),compute,json,root.toString());
    }
    @Test void replacementCancelsRemoteWorkAndInvalidatesOldTask() {
        var service=service("replacement");service.tick();
        verify(compute).call(connection,"/internal/v1/audio-roles/tasks/"+id+"/cancel","POST",null);
        verify(mapper).updateJob(eq(id),eq("FAILED"),eq("{}"),eq("源媒体已变化"),anyLong());
    }
    @Test void removedSongsDoNotRequeueForever() {
        var service=service("current");when(songs.get("song")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        service.tick();
        verify(mapper).updateJob(eq(id),eq("FAILED"),eq("{}"),eq("源媒体已移除"),anyLong());
    }
    @Test void corruptedDerivativeIsRejectedAndTemporaryFileRemoved() throws Exception {
        var service=service("current");
        var result=json.createObjectNode().put("artifact",id+".m4a").put("size",1024).put("sha256","bad");
        var state=json.createObjectNode().put("state","SUCCEEDED");state.set("result",result);
        when(compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null)).thenReturn(state);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream(new byte[1024]));
        when(compute.artifact(connection,"/internal/v1/audio-roles/tasks/"+id+"/artifact",null)).thenReturn(response);
        service.tick();
        verify(mapper).updateJob(eq(id),eq("FAILED"),eq("{}"),anyString(),anyLong());
        assertThat(Files.exists(root.resolve(id+".m4a"))).isFalse();assertThat(Files.exists(root.resolve(id+".part"))).isFalse();
    }
    @Test void unknownAnalysisCanBeResubmittedButActiveAndConfidentResultsAreReused() {
        var service=service("current");when(compute.connection()).thenReturn(connection);when(connection.origin()).thenReturn(java.net.URI.create("http://compute:8000"));
        var prior=new HashMap<String,Object>();prior.put("id",id);prior.put("kind","ANALYZE");prior.put("state","SUCCEEDED");prior.put("result_json","{\"state\":\"UNKNOWN\"}");
        when(mapper.forSong("song","current")).thenReturn(List.of(prior));
        assertThat(service.submit("song","ANALYZE").path("id").asText()).isNotEqualTo(id);
        verify(mapper).insertJob(argThat(row->row.get("kind").equals("ANALYZE")));
        clearInvocations(mapper);
        prior.put("state","RUNNING");assertThat(service.submit("song","ANALYZE").path("id").asText()).isEqualTo(id);
        prior.put("state","SUCCEEDED");prior.put("result_json","{\"state\":\"CONFIDENT\"}");
        assertThat(service.submit("song","ANALYZE").path("id").asText()).isEqualTo(id);
        verify(mapper,never()).insertJob(any());
    }
    @Test void statusExposesUnknownReasonAndEvidenceForOldAndNewAnalyses() {
        var service=service("current");
        var roleService=mock(AudioRoleService.class);when(roleService.role("song")).thenReturn(json.createObjectNode().put("state","UNKNOWN"));
        service=new AudioJobService(mapper,roleService,songs,mock(MediaSourceRepository.class),mock(MediaPlaybackService.class),compute,json,root.toString());
        when(mapper.forSong("song","current")).thenReturn(List.of(Map.of("id",id,"kind","ANALYZE","state","SUCCEEDED","result_json","{\"state\":\"UNKNOWN\",\"reason\":\"CONFLICTING_WINDOWS\",\"evidence\":{\"analysisVersion\":\"vocal-activity-v2\",\"vocalRatio\":[[0.6,0.01],[0.01,0.6]]}}")));
        var result=service.status("song");
        assertThat(result.at("/jobs/0/resultState").asText()).isEqualTo("UNKNOWN");
        assertThat(result.at("/jobs/0/reason").asText()).isEqualTo("CONFLICTING_WINDOWS");
        assertThat(result.at("/jobs/0/evidence/vocalRatio").size()).isEqualTo(2);
    }
    @Test void discoveryQueuesFastOnlyAndDoesNotLoopOnUnknown() throws Exception {
        var service=service("current");when(compute.connection()).thenReturn(connection);
        when(connection.origin()).thenReturn(java.net.URI.create("http://compute:8000"));
        when(compute.call(connection,"/internal/v1/status","GET",null)).thenReturn(json.readTree("{\"capabilities\":[\"media-probe\",\"audio-role-fast\",\"audio-role-fast-classifier\"]}"));
        when(mapper.candidates(anyString())).thenReturn(List.of("song"));
        var probe=Map.<String,Object>of("kind","PROBE","state","SUCCEEDED","result_json","{\"streams\":[{},{}]}");
        when(mapper.forSong("song","current")).thenReturn(List.of(probe));
        service.discover();verify(mapper).insertJob(argThat(row->row.get("kind").equals("FAST")));
        clearInvocations(mapper);
        when(mapper.forSong("song","current")).thenReturn(List.of(Map.of("kind","FAST","state","SUCCEEDED","result_json","{\"state\":\"UNKNOWN\",\"reason\":\"AMBIGUOUS_VOCALS\"}"),probe));
        service.discover();verify(mapper,never()).insertJob(any());
        when(mapper.forSong("song","current")).thenReturn(List.of(Map.of("kind","FAST","state","SUCCEEDED","result_json","{\"state\":\"UNKNOWN\",\"reason\":\"FAST_MODEL_MISSING\"}"),probe));
        service.discover();verify(mapper).insertJob(argThat(row->row.get("kind").equals("FAST")));
    }
    @Test void discoveryPreservesConfirmedRolesAndProbesNewSongs() throws Exception {
        service("current");when(compute.connection()).thenReturn(connection);
        when(connection.origin()).thenReturn(java.net.URI.create("http://compute:8000"));
        when(compute.call(connection,"/internal/v1/status","GET",null)).thenReturn(json.readTree("{\"capabilities\":[\"media-probe\",\"audio-role-fast\"]}"));
        when(mapper.candidates(anyString())).thenReturn(List.of("song"));
        var roles=mock(AudioRoleService.class);when(roles.role("song")).thenReturn(json.createObjectNode().put("state","CONFIRMED"));
        var service=new AudioJobService(mapper,roles,songs,mock(MediaSourceRepository.class),mock(MediaPlaybackService.class),compute,json,root.toString());
        when(mapper.forSong("song","current")).thenReturn(List.of(Map.of("kind","PROBE","state","SUCCEEDED","result_json","{\"streams\":[{},{}]}")));
        service.discover();verify(mapper,never()).insertJob(any());
        when(mapper.forSong("song","current")).thenReturn(List.of());
        service.discover();verify(mapper).insertJob(argThat(row->row.get("kind").equals("PROBE")));
    }

    @Test void upgradedPairedClassifierRetriesOldUnknownOnceOnlyWhenNodeIsUpgraded() throws Exception {
        var service=service("current");when(compute.connection()).thenReturn(connection);
        when(connection.origin()).thenReturn(java.net.URI.create("http://compute:8000"));
        var capability=json.readTree("{\"capabilities\":[\"media-probe\",\"audio-role-fast\",\"audio-role-fast-classifier\"]}");
        when(compute.call(connection,"/internal/v1/status","GET",null)).thenReturn(capability);
        when(mapper.candidates(anyString())).thenReturn(List.of("song"));
        var probe=Map.<String,Object>of("kind","PROBE","state","SUCCEEDED","result_json","{\"streams\":[{},{}]}");
        var prior=new HashMap<String,Object>();prior.put("kind","FAST");prior.put("state","SUCCEEDED");
        prior.put("result_json","{\"state\":\"UNKNOWN\",\"evidence\":{\"analysisVersion\":\"fast-vocal-v1\"}}");
        when(mapper.forSong("song","current")).thenReturn(List.of(prior,probe));
        service.discover();verify(mapper,never()).insertJob(any());
        ((com.fasterxml.jackson.databind.node.ArrayNode)capability.get("capabilities")).add("audio-role-fast-v2");
        service.discover();verify(mapper).insertJob(argThat(row->row.get("kind").equals("FAST")));
        clearInvocations(mapper);
        prior.put("result_json","{\"state\":\"UNKNOWN\",\"evidence\":{\"analysisVersion\":\"fast-vocal-v2\"}}");
        service.discover();verify(mapper,never()).insertJob(any());
    }

}
