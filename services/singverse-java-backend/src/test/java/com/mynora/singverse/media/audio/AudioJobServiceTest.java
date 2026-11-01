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
}
