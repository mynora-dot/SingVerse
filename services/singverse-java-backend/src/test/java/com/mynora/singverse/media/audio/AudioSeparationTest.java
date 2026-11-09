package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AudioSeparationTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final AudioRoleMapper mapper=mock(AudioRoleMapper.class);
    final MediaSongRepository songs=mock(MediaSongRepository.class);
    final AudioRoleService roles=mock(AudioRoleService.class);
    final TranscodeClient compute=mock(TranscodeClient.class);
    final Song song=mock(Song.class);
    final List<Map<String,Object>> history=new ArrayList<>();
    AudioJobService service;
    @BeforeEach void init(){
        when(song.id()).thenReturn("song");when(song.contentFingerprint()).thenReturn("fp");when(songs.get("song")).thenReturn(song);
        when(roles.view(eq(song),any())).thenAnswer(call->json.createObjectNode().put("state","UNKNOWN"));
        when(roles.role("song")).thenReturn(json.createObjectNode().put("state","UNKNOWN"));
        when(mapper.forSong("song","fp")).thenAnswer(call->new ArrayList<>(history));
        var connection=mock(Connection.class);when(connection.origin()).thenReturn(URI.create("http://127.0.0.1:8090"));when(compute.connection()).thenReturn(connection);
        doAnswer(call->{Map<String,Object> row=call.getArgument(0);history.addFirst(new HashMap<>(row));return null;}).when(mapper).insertJob(anyMap());
        service=new AudioJobService(mapper,roles,songs,mock(MediaPlaybackService.class),compute,json,root.toString());
    }
    AudioRecipe spec(String profile,String format,boolean preview){return new AudioRecipe(profile,null,format,preview,null);}
    @Test void cleanCpuFallbackAllowsAdmissionWhenPreferredDeviceIsUnavailable()throws Exception{
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"cpuFallback\":true,\"separation\":{\"version\":1,\"profiles\":[{\"id\":\"AI_CLEAN\",\"ready\":true}],\"backends\":[{\"id\":\"cpu\",\"state\":\"AVAILABLE\"},{\"id\":\"cuda\",\"state\":\"FAILED\"}]}}");
        var recipe=spec(AudioRecipe.CLEAN,"aac",false);
        assertThat(recipe.ready(node,"cuda")).isTrue();
        node.put("cpuFallback",false);
        assertThat(recipe.ready(node,"cuda")).isFalse();
        assertThat(recipe.ready(node,"cpu")).isTrue();
    }
    @Test void differentModesPreviewAndFlacHaveIndependentTasks(){
        Set<String> ids=new HashSet<>();
        for(var recipe:List.of(spec(AudioRecipe.DSP,"aac",false),spec(AudioRecipe.CLEAN,"aac",false),spec(AudioRecipe.DSP,"aac",true),spec(AudioRecipe.DSP,"flac",false)))
            ids.add(service.submit("song","SEPARATE","",List.of(),recipe).path("id").asText());
        assertThat(ids).hasSize(4);
        String again=service.submit("song","SEPARATE","",List.of(),spec(AudioRecipe.DSP,"aac",false)).path("id").asText();
        assertThat(ids).contains(again);verify(mapper,times(4)).insertJob(anyMap());
    }
    Map<String,Object> completed(String id,AudioRecipe recipe)throws Exception{
        Files.write(root.resolve(id+"."+recipe.extension()),new byte[]{1});
        var row=new HashMap<String,Object>();row.put("id",id);row.put("song_id","song");row.put("kind","SEPARATE");row.put("state","SUCCEEDED");row.put("fingerprint","fp");row.put("recipe_json",recipe.view(json).toString());row.put("result_json","{\"duration\":12}");history.add(row);when(mapper.job(id)).thenReturn(row);return row;
    }
    @Test void previewAndFlacNeverBecomePlaybackAndOldSelectionIsRetained()throws Exception{
        completed("preview",spec(AudioRecipe.DSP,"aac",true));completed("export",spec(AudioRecipe.DSP,"flac",false));
        assertThat(service.status("song").has("backingResource")).isFalse();
        completed("new",spec(AudioRecipe.DSP,"aac",false));completed("old",AudioRecipe.legacy());
        when(mapper.selection("song")).thenReturn(Map.of("song_id","song","job_id","old","fingerprint","fp"));
        assertThat(service.status("song").at("/backingResource/jobId").asText()).isEqualTo("old");
        assertThatThrownBy(()->service.activate("song","preview")).hasMessageContaining("完整 AAC");
        assertThatThrownBy(()->service.activate("song","export")).hasMessageContaining("完整 AAC");
        service.activate("song","new");verify(mapper).updateSelection(eq("song"),eq("fp"),eq("new"),anyLong());
    }
    @Test void sourceReplacementMakesEarlierVersionUnavailable()throws Exception{
        completed("old",AudioRecipe.legacy());when(song.contentFingerprint()).thenReturn("changed");
        assertThatThrownBy(()->service.activate("song","old")).hasMessageContaining("完整 AAC");
    }
    @Test void cachedDspDoesNotSkipCleanBatchAndPreviewDoesNotSkipFullTrack()throws Exception{
        var status=json.readTree("{\"streams\":[{}],\"jobs\":[],\"versions\":[{\"separationProfile\":\"DSP_FAST\",\"outputFormat\":\"aac\",\"preview\":true}]}");
        assertThat(AudioProcessingService.category(status,spec(AudioRecipe.DSP,"aac",false))).isEqualTo("ELIGIBLE");
        ((com.fasterxml.jackson.databind.node.ObjectNode)status.at("/versions/0")).put("preview",false);
        assertThat(AudioProcessingService.category(status,spec(AudioRecipe.DSP,"aac",false))).isEqualTo("READY");
        assertThat(AudioProcessingService.category(status,spec(AudioRecipe.CLEAN,"aac",false))).isEqualTo("ELIGIBLE");
        assertThat(AudioProcessingService.category(status,AudioRecipe.legacy())).isEqualTo("ELIGIBLE");
    }
}
