package com.mynora.singverse.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mynora.singverse.media.dto.*;
import com.mynora.singverse.media.repository.*;
import jakarta.servlet.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class TaskMediaStreamTest {
    @TempDir Path root;
    @Test void revocationOrSourceChangeStopsAnAlreadyOpenMediaResponse() throws Exception {
        Files.write(root.resolve("song.mp3"), new byte[3 * 65536]);
        var songs=mock(MediaSongRepository.class);var sources=mock(MediaSourceRepository.class);
        var song=mock(MediaSongDtos.Song.class);var source=mock(MediaSourceDtos.Source.class);
        when(songs.get("song")).thenReturn(song);when(song.sourceId()).thenReturn("source");when(song.relativePath()).thenReturn("song.mp3");
        when(sources.get("source")).thenReturn(source);when(source.type()).thenReturn("LOCAL");when(source.location()).thenReturn(root.toString());
        var service=new MediaPlaybackService(songs,sources,null);
        for (var status: java.util.List.of(HttpStatus.GONE,HttpStatus.CONFLICT)) {
            var active=new AtomicBoolean(true);
            var response=new MockHttpServletResponse(){
                @Override public ServletOutputStream getOutputStream(){
                    var delegate=super.getOutputStream();
                    return new ServletOutputStream(){
                        public boolean isReady(){return true;}
                        public void setWriteListener(WriteListener listener){}
                        public void write(int value)throws java.io.IOException{delegate.write(value);}
                        @Override public void write(byte[] data,int offset,int length)throws java.io.IOException{
                            delegate.write(data,offset,length);
                            active.set(false);
                            if(status==HttpStatus.CONFLICT)Files.writeString(root.resolve("song.mp3"),"changed");
                            try {Thread.sleep(275);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.IOException(error);}
                        }
                    };
                }
            };
            Files.write(root.resolve("song.mp3"), new byte[3 * 65536]);
            when(song.fileSize()).thenReturn(3L * 65536);
            when(song.modifiedAt()).thenReturn(Files.getLastModifiedTime(root.resolve("song.mp3")).toInstant().toString());
            assertThatThrownBy(()->service.play("song",null,false,response,()->{
                if(status==HttpStatus.GONE && !active.get())throw new ResponseStatusException(status,"Task ended");
            })).isInstanceOf(ResponseStatusException.class);
            assertThat(response.getContentAsByteArray()).hasSize(65536);
        }
    }
}
