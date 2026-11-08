package com.mynora.singverse.media.artist;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mynora.singverse.media.audio.AudioCatalog;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class LibraryChangeInterceptorTest {
    @Test void strictMyBatisParametersWithoutRowStillPublishOnlyTheAffectedSong() throws Throwable {
        var events=mock(ApplicationEventPublisher.class);var executor=mock(Executor.class);var statement=mock(MappedStatement.class);
        when(statement.getId()).thenReturn("com.mynora.singverse.media.audio.AudioRoleMapper.insertClaim");
        when(executor.update(any(),any())).thenReturn(1);
        var parameters=new MapperMethod.ParamMap<Object>();parameters.put("song","song-id");parameters.put("claim","{}");
        var invocation=new Invocation(executor,Executor.class.getMethod("update",MappedStatement.class,Object.class),new Object[]{statement,parameters});
        assertThat(new LibraryChangeInterceptor(events).intercept(invocation)).isEqualTo(1);
        verify(events).publishEvent(new AudioCatalog.Changed("song-id"));
    }
}
