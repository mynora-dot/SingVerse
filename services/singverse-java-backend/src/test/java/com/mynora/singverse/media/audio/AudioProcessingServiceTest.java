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
    final AudioCatalog catalog=mock(AudioCatalog.class);
    final org.springframework.transaction.PlatformTransactionManager transactions=mock(org.springframework.transaction.PlatformTransactionManager.class);
    final AudioProcessingService service=new AudioProcessingService(mapper,roles,jobs,songs,compute,json,catalog,transactions);
    @AfterEach void close(){service.close();}
    Map<String,Object> item(String state,String job){var row=new HashMap<String,Object>();row.put("batch_id","batch");row.put("batch_state","QUEUED");row.put("song_id","song");row.put("fingerprint","current");row.put("state",state);row.put("job_id",job);row.put("backend","cpu");return row;}
    @BeforeEach void init(){
        when(transactions.getTransaction(any())).thenAnswer(call->new org.springframework.transaction.support.SimpleTransactionStatus());
        when(mapper.lockPreparing(anyString(),anyLong())).thenReturn(1);
        var song=mock(Song.class);when(song.id()).thenReturn("song");when(song.contentFingerprint()).thenReturn("current");when(songs.get("song")).thenReturn(song);
        when(songs.byIds(anyList())).thenReturn(List.of(song));
        when(roles.jobsByIds(anyList())).thenAnswer(call->{
            List<String> ids=call.getArgument(0);return ids.stream().map(id->{
                var job=roles.job(id);if(job==null)return Map.<String,Object>of();
                var row=new HashMap<>(job);row.put("id",id);return row;
            }).filter(row->!row.isEmpty()).toList();
        });
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
        var summary=new AudioCatalog.Summary("song","source","测试",List.of("歌手"),"current",1,"CONFIRMED","READY",false);
        when(catalog.current()).thenReturn(new AudioCatalog.Snapshot(List.of(summary),Map.of("song",summary),1,1));
        when(songs.byIds(List.of("song"))).thenReturn(List.of(song));
        service.preview(new AudioProcessingService.Selection(null,List.of("song","song"),false,"openvino"));
        verify(mapper).insertBatch(anyString(),eq("openvino"),anyLong(),eq(1));
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Map<String,Object>>> captured=ArgumentCaptor.forClass(List.class);
        verify(mapper,timeout(2000)).insertItems(captured.capture());
        assertThat(captured.getValue()).hasSize(1);
        assertThat(captured.getValue().getFirst()).containsEntry("state","SKIPPED").containsEntry("fingerprint","current").containsEntry("category","READY");
    }
    @Test void unconfirmedFilterOnlyMatchesDualStreamsWithoutConfirmedRoles(){
        var filter=new AudioProcessingService.Filter("","",null,null,"UNCONFIRMED",null);
        assertThat(new AudioCatalog.Summary("song","source","",List.of(),"fp",2,"UNKNOWN","INAPPLICABLE",false).matches(filter)).isTrue();
        assertThat(new AudioCatalog.Summary("song","source","",List.of(),"fp",2,"CONFIRMED","INAPPLICABLE",false).matches(filter)).isFalse();
        assertThat(new AudioCatalog.Summary("song","source","",List.of(),"fp",1,"CONFIRMED","ELIGIBLE",false).matches(filter)).isFalse();
        assertThat(new AudioCatalog.Summary("song","source","",List.of(),"fp",null,"UNKNOWN","UNPROBED",false).matches(filter)).isFalse();
    }
    @Test void startRequiresQualifiedBackendAndResubmissionDoesNotCreateJobs(){
        when(compute.call(any(),eq("/internal/v1/audio-roles/models"),eq("GET"),isNull())).thenReturn(json.createObjectNode().putNull("selected"));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("试跑");verify(mapper,never()).start(anyString(),anyLong());
        when(compute.call(any(),eq("/internal/v1/audio-roles/models"),eq("GET"),isNull())).thenReturn(json.createObjectNode().put("selected","cpu").put("resourceReady",false));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("空间不足");verify(mapper,never()).start(anyString(),anyLong());
        when(mapper.batch("batch")).thenReturn(Map.of("id","batch","state","QUEUED","backend","","created_at",1L));service.start("batch");verify(mapper,never()).start(anyString(),anyLong());
    }
    @Test void songRowOnlyAdvertisesConfirmedNonStaleCover() {
        var song=songs.get("song");
        var parsed=mock(com.mynora.singverse.media.filename.FilenameParseResult.class);
        when(parsed.title()).thenReturn("测试");when(parsed.artists()).thenReturn(List.of("歌手"));when(song.result()).thenReturn(parsed);
        when(jobs.status("song")).thenReturn(json.createObjectNode());when(song.id()).thenReturn("song");
        assertThat(service.row(song,json.createObjectNode()).path("coverAvailable").asBoolean()).isFalse();
        var metadata=mock(com.mynora.singverse.media.metadata.MetadataDtos.Applied.class);
        var track=mock(com.mynora.singverse.media.metadata.ExternalTrack.class);
        when(song.metadata()).thenReturn(metadata);when(metadata.fields()).thenReturn(Set.of("cover"));when(metadata.track()).thenReturn(track);when(track.coverUrl()).thenReturn("asset:cover");
        assertThat(service.row(song,json.createObjectNode()).path("coverAvailable").asBoolean()).isTrue();
        when(metadata.stale()).thenReturn(true);
        assertThat(service.row(song,json.createObjectNode()).path("coverAvailable").asBoolean()).isFalse();
        verify(jobs,never()).submit(anyString(),anyString());
    }
    @Test void hardwarePresenceRejectsManualCpuAtBatchStart() throws Exception {
        when(mapper.batch("batch")).thenReturn(Map.of("id","batch","state","PREVIEW","backend","cpu","created_at",1L));
        when(compute.call(any(),eq("/internal/v1/audio-roles/models"),eq("GET"),isNull())).thenReturn(json.readTree("{\"selected\":\"cpu\",\"backends\":[{\"id\":\"cuda\",\"detected\":true,\"state\":\"UNVERIFIED\"}]}"));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("CPU 仅用于自动降级");
        verify(mapper,never()).start(anyString(),anyLong());
    }

    @Test void preparationIsChunkedAndMaterialChangesAreRecordedWithoutSubmittingJobs() {
        var selected=new ArrayList<AudioCatalog.Summary>();
        for(int index=0;index<401;index++)selected.add(new AudioCatalog.Summary("song-"+index,"source","歌曲",List.of(),"current",null,"UNKNOWN","UNPROBED",false));
        when(songs.byIds(anyList())).thenAnswer(call->{
            List<String> ids=call.getArgument(0);return ids.stream().map(id->{
                var song=mock(Song.class);when(song.id()).thenReturn(id);when(song.contentFingerprint()).thenReturn(id.equals("song-0")?"changed":"current");return song;
            }).toList();
        });
        service.prepare("batch",selected);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Map<String,Object>>> chunks=ArgumentCaptor.forClass(List.class);
        verify(mapper,times(3)).insertItems(chunks.capture());
        assertThat(chunks.getAllValues()).extracting(List::size).containsExactly(200,200,1);
        assertThat(chunks.getAllValues().getFirst().getFirst()).containsEntry("state","FAILED");
        verify(transactions,times(3)).getTransaction(any());verify(mapper).ready(eq("batch"),anyLong());
        verify(jobs,never()).submit(anyString(),anyString());
    }
    @Test void preparingBatchCannotStartAndCancelledPreparationStopsBeforeTheNextChunk() {
        when(mapper.batch("batch")).thenReturn(Map.of("id","batch","state","PREPARING","backend","","created_at",1L));
        assertThatThrownBy(()->service.start("batch")).hasMessageContaining("准备中");
        when(mapper.lockPreparing(eq("batch"),anyLong())).thenReturn(0);
        service.prepare("batch",List.of(new AudioCatalog.Summary("song","source","歌曲",List.of(),"current",null,"UNKNOWN","UNPROBED",false)));
        verify(mapper,never()).insertItems(anyList());verify(mapper,never()).ready(anyString(),anyLong());
    }
    @Test void cancellingAPreviewDoesNotScanEverySnapshotMember() {
        service.cancel("batch");
        verify(mapper).items("batch",50,0);
        verify(mapper,never()).items(eq("batch"),eq(100),anyLong());
        verify(jobs,never()).cancel(anyString(),anyString());
    }

    @Test void unchangedRunningMembersUseBulkReadsAndDoNotRewriteRows() {
        when(mapper.pending()).thenReturn(List.of(item("PROCESSING","work")));
        doReturn(List.of(Map.of("id","work","state","RUNNING","error",""))).when(roles).jobsByIds(List.of("work"));
        service.tick();
        verify(songs).byIds(List.of("song"));verify(roles).jobsByIds(List.of("work"));
        verify(mapper,never()).updateItem(anyString(),anyString(),anyString(),any(),anyString(),anyLong());
        verify(jobs,never()).status(anyString());verify(roles,never()).job(anyString());
    }

}
