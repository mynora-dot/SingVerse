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
    @Test void queueTimingSurvivesLegacyNodeResponsesWithoutChangingTheSubmissionContract(){
        var service=service("current");
        var queued=new HashMap<String,Object>(mapper.pending().getFirst());
        queued.put("state","QUEUED");queued.put("kind","PROBE");queued.put("error","");queued.put("created_at",System.currentTimeMillis()-5000);
        var current=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>(queued);
        when(mapper.pending()).thenAnswer(call->List.of(new HashMap<>(current.get())));
        when(mapper.job(id)).thenAnswer(call->new HashMap<>(current.get()));
        doAnswer(call->{var next=new HashMap<>(current.get());next.put("state",call.getArgument(1));next.put("result_json",call.getArgument(2));next.put("error",call.getArgument(3));current.set(next);return 1;})
                .when(mapper).updateJob(eq(id),anyString(),anyString(),anyString(),anyLong());
        when(compute.call(eq(connection),eq("/internal/v1/audio-roles/tasks"),eq("POST"),any())).thenAnswer(call->{
            com.fasterxml.jackson.databind.JsonNode body=call.getArgument(3);var fields=new HashSet<String>();body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id","reference","mode");
            return json.createObjectNode().put("state","RUNNING");
        });
        var response=json.createObjectNode().put("state","RUNNING");response.putObject("result");
        when(compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null)).thenReturn(response);
        service.tick();
        long queue=queueTime(current.get());
        assertThat(queue).isGreaterThanOrEqualTo(5000);
        clearInvocations(mapper);service.tick();
        verify(mapper,never()).updateJob(anyString(),anyString(),anyString(),anyString(),anyLong());
        response.put("state","SUCCEEDED");service.tick();
        assertThat(current.get().get("state")).isEqualTo("SUCCEEDED");
        assertThat(queueTime(current.get())).isEqualTo(queue);
    }
    private long queueTime(Map<String,Object> row){
        try{return json.readTree(row.get("result_json").toString()).at("/timingsMs/queue").asLong();}
        catch(IOException failure){throw new AssertionError(failure);}
    }
    @Test void unchangedRunningResultDoesNotWriteOrSubmitOtherQueuedWork() throws Exception {
        var service=service("current");
        var running=new HashMap<String,Object>(mapper.pending().getFirst());running.put("error","");
        var queued=new HashMap<String,Object>(running);queued.put("id",UUID.randomUUID().toString());queued.put("state","QUEUED");
        when(mapper.pending()).thenReturn(List.of(running,queued));when(mapper.job(id)).thenReturn(running);
        when(compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null)).thenReturn(json.readTree("{\"state\":\"RUNNING\",\"result\":{}}"));
        service.tick();
        verify(mapper,never()).updateJob(anyString(),anyString(),anyString(),anyString(),anyLong());
        verify(compute,never()).call(any(),eq("/internal/v1/audio-roles/tasks"),eq("POST"),any());
    }
    AudioJobService service(String fingerprint) {
        var song=mock(Song.class);when(song.contentFingerprint()).thenReturn(fingerprint);when(songs.get("song")).thenReturn(song);
        when(compute.connection("node")).thenReturn(connection);
        when(mapper.pending()).thenReturn(List.of(Map.of("id",id,"song_id","song","kind","SEPARATE","state","RUNNING","fingerprint","current","node_origin","node","result_json","{}")));
        return new AudioJobService(mapper,mock(AudioRoleService.class),songs,mock(MediaPlaybackService.class),compute,json,root.toString());
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
    @Test void cancellingAStalledDownloadClosesItsBodyAndDoesNotResurrectTheJob() throws Exception {
        var service=service("current");
        var current=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>(new HashMap<>(mapper.pending().getFirst()));
        when(mapper.job(id)).thenAnswer(call->new HashMap<>(current.get()));
        doAnswer(call->{var next=new HashMap<>(current.get());next.put("state",call.getArgument(1));current.set(next);return null;})
                .when(mapper).updateJob(eq(id),anyString(),anyString(),anyString(),anyLong());
        var result=json.createObjectNode().put("artifact",id+".m4a").put("size",1024).put("sha256","unused");
        var state=json.createObjectNode().put("state","SUCCEEDED");state.set("result",result);
        when(compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null)).thenReturn(state);
        var reading=new java.util.concurrent.CountDownLatch(1);
        var closed=new java.util.concurrent.CountDownLatch(1);
        var input=new InputStream(){
            @Override public int read()throws IOException {return read(new byte[1],0,1);}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                reading.countDown();
                try{if(!closed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Test did not close body");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                throw new IOException("Body closed");
            }
            @Override public void close(){closed.countDown();}
        };
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(input);
        when(compute.artifact(connection,"/internal/v1/audio-roles/tasks/"+id+"/artifact",null)).thenReturn(response);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var work=executor.submit(service::tick);
            assertThat(reading.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),()->service.cancel("song",id));
            work.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(current.get().get("state")).isEqualTo("CANCELLED");
            verify(mapper,never()).updateJob(eq(id),eq("SUCCEEDED"),anyString(),anyString(),anyLong());
            assertThat(Files.exists(root.resolve(id+".part"))).isFalse();
            assertThat(Files.exists(root.resolve(id+".m4a"))).isFalse();
        }finally{input.close();executor.shutdownNow();}
    }
    @Test void cancellingWhileSubmissionIsInFlightCancelsAgainAfterRemoteAcceptance() {
        var service=service("current");
        var queued=new HashMap<>(mapper.pending().getFirst());queued.put("state","QUEUED");
        when(mapper.pending()).thenReturn(List.of(queued));
        var current=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>(queued);
        when(mapper.job(id)).thenAnswer(call->new HashMap<>(current.get()));
        doAnswer(call->{var next=new HashMap<>(current.get());next.put("state",call.getArgument(1));current.set(next);return null;})
                .when(mapper).updateJob(eq(id),anyString(),anyString(),anyString(),anyLong());
        when(compute.call(eq(connection),eq("/internal/v1/audio-roles/tasks"),eq("POST"),any()))
                .thenAnswer(call->{service.cancel("song",id);return json.createObjectNode().put("state","QUEUED");});
        service.tick();
        assertThat(current.get().get("state")).isEqualTo("CANCELLED");
        verify(songs,times(1)).get("song");
        verify(compute).call(eq(connection),eq("/internal/v1/audio-roles/tasks"),eq("POST"),argThat(body ->
                id.equals(body.path("id").asText()) && id.equals(body.path("reference").asText())
                        && "SEPARATE".equals(body.path("mode").asText())));
        verify(compute,times(2)).call(connection,"/internal/v1/audio-roles/tasks/"+id+"/cancel","POST",null);
    }
    @Test void sourceReplacementDuringDownloadRemovesTheCompletedDerivative() throws Exception {
        var service=service("current");
        var song=songs.get("song");
        byte[] content=new byte[1024];
        String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        var result=json.createObjectNode().put("artifact",id+".m4a").put("size",content.length).put("sha256",digest);
        var state=json.createObjectNode().put("state","SUCCEEDED");state.set("result",result);
        when(compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null)).thenReturn(state);
        var input=new ByteArrayInputStream(content){
            @Override public synchronized int read(byte[] bytes,int offset,int length){
                int count=super.read(bytes,offset,length);
                if(count<0)when(song.contentFingerprint()).thenReturn("replacement");
                return count;
            }
        };
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(input);
        when(compute.artifact(connection,"/internal/v1/audio-roles/tasks/"+id+"/artifact",null)).thenReturn(response);
        service.tick();
        verify(mapper).updateJob(eq(id),eq("FAILED"),eq("{}"),anyString(),anyLong());
        verify(mapper,never()).updateJob(eq(id),eq("SUCCEEDED"),anyString(),anyString(),anyLong());
        assertThat(Files.exists(root.resolve(id+".part"))).isFalse();
        assertThat(Files.exists(root.resolve(id+".m4a"))).isFalse();
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
        var roleService=mock(AudioRoleService.class);when(roleService.view(any(),nullable(Map.class))).thenReturn(json.createObjectNode().put("state","UNKNOWN"));
        service=new AudioJobService(mapper,roleService,songs,mock(MediaPlaybackService.class),compute,json,root.toString());
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
        var service=new AudioJobService(mapper,roles,songs,mock(MediaPlaybackService.class),compute,json,root.toString());
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
