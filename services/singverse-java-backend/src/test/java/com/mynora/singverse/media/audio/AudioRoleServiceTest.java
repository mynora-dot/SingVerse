package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSongDtos;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.metadata.MetadataFileMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AudioRoleServiceTest {
    final AudioRoleMapper mapper=mock(AudioRoleMapper.class);
    final MediaSongRepository songs=mock(MediaSongRepository.class);
    final ObjectMapper json=new ObjectMapper();
    AudioRoleService service(){
        when(mapper.role("song")).thenReturn(null);
        var song=mock(MediaSongDtos.Song.class);when(song.contentFingerprint()).thenReturn("current");when(songs.get("song")).thenReturn(song);
        var service=new AudioRoleService(mapper,songs,json);ReflectionTestUtils.setField(service,"files",mock(MetadataFileMapper.class));return service;
    }
    @Test void staleRolesAreNotUsed(){
        var service=service();when(mapper.role("song")).thenReturn(Map.of("fingerprint","old","revision",4));
        var result=service.role("song");assertThat(result.path("state").asText()).isEqualTo("UNKNOWN");assertThat(result.has("backingId")).isFalse();
    }
    @Test void inventedTracksAndRevisionConflictsCannotOverwrite(){
        var service=service();when(mapper.forSong("song","current")).thenReturn(List.of(Map.of("result_json","{\"streams\":[{\"id\":\"stream:1\"},{\"id\":\"stream:2\"}]}")));
        when(mapper.role("song")).thenReturn(Map.of("revision",2));
        assertThatThrownBy(()->service.correct("song","stream:1","stream:99",2)).hasMessageContaining("探测");
        assertThatThrownBy(()->service.correct("song","stream:1","stream:2",1)).hasMessageContaining("变化");
        verify(mapper,never()).updateRole(any());
    }
    @Test void modelResultsCannotOverrideManualOrChangedMedia()throws Exception{
        var service=service();var suggestion=json.readTree("{\"state\":\"CONFIDENT\",\"original\":\"stream:1\",\"backing\":\"stream:2\"}");
        when(mapper.role("song")).thenReturn(Map.of("origin","MANUAL","fingerprint","current","revision",2));
        service.applySuggestion("song","current",suggestion);service.applySuggestion("song","old",suggestion);
        verify(mapper,never()).updateRole(any());verify(mapper,never()).insertRole(any());
    }
    @Test void sharedClaimsRequireMatchingProbeAndNeverCarryDerivedReferences()throws Exception{
        var service=service();
        var claim=json.readTree("{\"state\":\"CONFIRMED\",\"originalId\":\"stream:1\",\"backingId\":\"stream:2\",\"duration\":180,\"streams\":[{\"id\":\"stream:1\"},{\"id\":\"stream:2\"}]}");
        var envelope=json.createObjectNode().put("fingerprint","current");envelope.set("claim",claim);
        when(mapper.claim("song")).thenReturn(envelope.toString());
        service.verifyImported("song","replacement",claim);
        verify(mapper,never()).insertRole(any());
        service.verifyImported("song","current",json.readTree("{\"duration\":181,\"streams\":[{\"id\":\"stream:1\"},{\"id\":\"stream:2\"}]}"));
        verify(mapper,never()).insertRole(any());
        service.verifyImported("song","current",claim);
        verify(mapper).insertRole(argThat(row->row.get("origin").equals("SHARED")&&row.get("original_id").equals("stream:1")));
    }
    @Test void fastSuggestionsCannotOverwriteAnyConfirmedRole() throws Exception {
        var service=service();
        var suggestion=json.readTree("{\"state\":\"CONFIDENT\",\"original\":\"stream:1\",\"backing\":\"stream:2\",\"evidence\":{\"analysisVersion\":\"fast-vocal-v1\"}}");
        for(String origin:List.of("AUTO","MANUAL","SHARED")){
            when(mapper.role("song")).thenReturn(Map.of("origin",origin,"fingerprint","current","revision",2));
            service.applySuggestion("song","current",suggestion);
        }
        verify(mapper,never()).updateRole(any());verify(mapper,never()).insertRole(any());
    }

}
