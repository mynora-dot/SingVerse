package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class AudioProcessingServiceTest {
    final AudioProcessingMapper mapper=mock(AudioProcessingMapper.class);
    final AudioRoleMapper roles=mock(AudioRoleMapper.class);
    final AudioJobService jobs=mock(AudioJobService.class);
    final MediaSongRepository songs=mock(MediaSongRepository.class);
    final TranscodeClient compute=mock(TranscodeClient.class);
    final ObjectMapper json=new ObjectMapper();
    final AudioProcessingService service=new AudioProcessingService(mapper,roles,jobs,songs,compute,json);
    Map<String,Object> item(String state,String job){var row=new HashMap<String,Object>();row.put("batch_id","batch");row.put("batch_state","QUEUED");row.put("song_id","song");row.put("fingerprint","current");row.put("state",state);row.put("job_id",job);row.put("backend","cpu");return row;}
    @BeforeEach void init(){
        var song=mock(Song.class);when(song.contentFingerprint()).thenReturn("current");when(songs.get("song")).thenReturn(song);
        when(mapper.batch(anyString())).thenAnswer(call->Map.of("id",call.getArgument(0),"state","PREVIEW","backend","","created_at",1L));
    }
    @Test void unprobedSongsSeparateOnlyAfterSingleStreamProbe()throws Exception{
        when(mapper.pending()).thenReturn(List.of(item("PENDING","")));
        when(jobs.status("song")).thenReturn(json.readTree("{\"jobs\":[]}").deepCopy());
        when(jobs.submit("song","PROBE","")).thenReturn(json.createObjectNode().put("id","probe"));service.tick();
        verify(mapper).updateItem(eq("batch"),eq("song"),eq("PROBING"),eq("probe"),eq(""),anyLong());
        when(mapper.pending()).thenReturn(List.of(item("PROBING","probe")));when(roles.job("probe")).thenReturn(Map.of("state","SUCCEEDED"));
        when(jobs.status("song")).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"streams\":[{\"id\":\"stream:0\"}],\"jobs\":[]}"));
        when(jobs.submit("song","SEPARATE","cpu")).thenReturn(json.createObjectNode().put("id","separate"));service.tick();
        verify(mapper).updateItem(eq("batch"),eq("song"),eq("PROCESSING"),eq("separate"),eq(""),anyLong());
    }
    @Test void dualStreamsAfterProbeAreSkipped()throws Exception{
        when(mapper.pending()).thenReturn(List.of(item("PROBING","probe")));when(roles.job("probe")).thenReturn(Map.of("state","SUCCEEDED"));
        when(jobs.status("song")).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"streams\":[{},{}],\"jobs\":[]}"));service.tick();
        verify(mapper).updateItem(eq("batch"),eq("song"),eq("SKIPPED"),isNull(),contains("非单音轨"),anyLong());verify(jobs,never()).submit(anyString(),eq("SEPARATE"),anyString());
    }
    @Test void cancelledBatchPreservesAlreadyCompletedArtifacts(){
        var item=item("PROCESSING","done");item.put("batch_state","CANCELLED");when(mapper.pending()).thenReturn(List.of(item));when(roles.job("done")).thenReturn(Map.of("state","SUCCEEDED"));service.tick();
        verify(jobs,never()).cancel(anyString(),anyString());verify(mapper).updateItem(eq("batch"),eq("song"),eq("SUCCEEDED"),eq("done"),eq(""),anyLong());
    }
    @Test void materialReplacementStopsOwnedTask(){
        var item=item("PROCESSING","work");item.put("fingerprint","old");when(mapper.pending()).thenReturn(List.of(item));service.tick();
        verify(jobs).cancel("song","work");verify(mapper).updateItem(eq("batch"),eq("song"),eq("FAILED"),eq("work"),contains("变化"),anyLong());
    }
    @Test void previewSnapshotsDistinctSelectionsAndSkipsReadySongs()throws Exception{
        var song=songs.get("song");when(song.id()).thenReturn("song");when(song.sourceId()).thenReturn("source");when(song.sourceName()).thenReturn("media");when(song.relativePath()).thenReturn("song.wav");
        var parsed=mock(com.mynora.singverse.media.filename.FilenameParseResult.class);when(parsed.title()).thenReturn("测试");when(parsed.artists()).thenReturn(List.of("歌手"));when(song.result()).thenReturn(parsed);
        when(jobs.status("song")).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"backingResource\":{},\"jobs\":[]}"));
        service.preview(new AudioProcessingService.Selection(null,List.of("song","song"),false,"openvino"));
        verify(mapper).insertBatch(anyString(),eq("openvino"),anyLong());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> captured=ArgumentCaptor.forClass(Map.class);verify(mapper,times(1)).insertItem(captured.capture());assertThat(captured.getValue()).containsEntry("state","SKIPPED").containsEntry("fingerprint","current").containsEntry("category","READY");
    }
    @Test void startRequiresQualifiedBackendAndResubmissionDoesNotCreateJobs(){
        when(compute.call(any(),eq("/internal/v1/audio-roles/models"),eq("GET"),isNull())).thenReturn(json.createObjectNode().putNull("selected"));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("试跑");verify(mapper,never()).start(anyString(),anyLong());
        when(compute.call(any(),eq("/internal/v1/audio-roles/models"),eq("GET"),isNull())).thenReturn(json.createObjectNode().put("selected","cpu").put("resourceReady",false));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("空间不足");verify(mapper,never()).start(anyString(),anyLong());
        when(mapper.batch("batch")).thenReturn(Map.of("id","batch","state","QUEUED","backend","","created_at",1L));service.start("batch");verify(mapper,never()).start(anyString(),anyLong());
    }
}
