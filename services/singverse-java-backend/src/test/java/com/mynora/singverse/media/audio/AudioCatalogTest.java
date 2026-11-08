package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class AudioCatalogTest {
    @TempDir Path temp;

    @Test void firstReadIsRetryableAndInvalidationRetainsThePublishedSnapshot() {
        var songs=mock(MediaSongRepository.class);
        var mapper=mock(AudioRoleMapper.class);
        try(var fixture=new Fixture(songs,mapper)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            when(songs.batchAfter(anyString(),isNull(),eq(200))).thenAnswer(call->{gate.await();return List.of();});
            assertThatThrownBy(fixture.catalog::current).isInstanceOfSatisfying(ResponseStatusException.class,error->{
                assertThat(error.getStatusCode().value()).isEqualTo(503);
                assertThat(error.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            });
            gate.countDown();
            org.awaitility.Awaitility.await().ignoreExceptions().atMost(java.time.Duration.ofSeconds(3))
                    .untilAsserted(()->assertThat(fixture.catalog.current().songs()).isEmpty());
            fixture.catalog.changed(new AudioCatalog.Changed());
            assertThat(fixture.catalog.current().songs()).isEmpty();
            assertThat(fixture.catalog.building()).isTrue();
        }
    }

    @Test void tenAndHundredThousandSongsUseBoundedBulkQueriesAndKeepOnlySummaries() {
        for(int total:List.of(10_000,100_000)) {
            var songs=mock(MediaSongRepository.class);
            var mapper=mock(AudioRoleMapper.class);
            var parsed=new KtvFilenameParser().parse("歌手-歌曲.mkv");
            java.util.function.Function<String,Song> songById=id->new Song(id,"source","曲库","歌曲.mkv",10L,
                    "2026-11-07T00:00:00Z","2026-11-07T00:00:00Z","default",0,null,parsed,null,null,"current");
            when(songs.batchAfter(anyString(),isNull(),eq(200))).thenAnswer(call->{
                String cursor=call.getArgument(0);int from=cursor.isEmpty()?0:Integer.parseInt(cursor)+1;
                var batch=new ArrayList<Song>();
                for(int index=from;index<Math.min(total,from+200);index++)
                    batch.add(songById.apply(String.format(Locale.ROOT,"%06d",index)));
                return batch;
            });
            when(songs.byIds(anyList())).thenAnswer(call->{List<String> ids=call.getArgument(0);return ids.stream().map(songById).toList();});
            try(var fixture=new Fixture(songs,mapper)) {
                long start=System.nanoTime(),before=allocated();
                fixture.catalog.rebuild();
                var index=fixture.catalog.current();
                long elapsed=(System.nanoTime()-start)/1_000_000,bytes=allocated()-before;
                assertThat(index.songs()).hasSize(total);assertThat(index.byId()).hasSize(total);
                assertThat(index.songs().getFirst().category()).isEqualTo("UNPROBED");
                int batches=total/200;
                verify(songs,times(batches+1)).batchAfter(anyString(),isNull(),eq(200));
                verify(mapper,times(batches)).rolesForSongs(argThat(ids->ids.size()==200));
                verify(mapper,times(batches)).jobsForSongs(argThat(ids->ids.size()==200));
                verify(songs,never()).get(anyString());verify(mapper,never()).role(anyString());
                verify(mapper,never()).forSong(anyString(),anyString());
                System.out.printf(Locale.ROOT,"AUDIO_CATALOG_SYNTHETIC songs=%d bulk_queries=%d elapsed_ms=%d allocated_bytes=%d%n",
                        total,batches*3+1,elapsed,bytes);
                var processing=new AudioProcessingService(mock(AudioProcessingMapper.class),mapper,fixture.jobs,songs,
                        mock(TranscodeClient.class),new ObjectMapper(),fixture.catalog,mock(org.springframework.transaction.PlatformTransactionManager.class));
                try {
                    start=System.nanoTime();before=allocated();
                    var page=processing.list(new AudioProcessingService.Filter("","",null,null,"",null),total/40,20);
                    elapsed=(System.nanoTime()-start)/1_000_000;bytes=allocated()-before;
                    assertThat(page.path("items")).hasSize(20);assertThat(page.path("totalElements").asInt()).isEqualTo(total);
                    verify(songs).byIds(argThat(ids->ids.size()==20));
                    verify(mapper).rolesForSongs(argThat(ids->ids.size()==20));
                    verify(mapper).jobsForSongs(argThat(ids->ids.size()==20));
                    System.out.printf(Locale.ROOT,"AUDIO_PAGE_SYNTHETIC songs=%d returned=20 bulk_queries=3 elapsed_ms=%d allocated_bytes=%d%n",
                            total,elapsed,bytes);
                    clearInvocations(songs,mapper);
                    String changed=String.format(Locale.ROOT,"%06d",total/2);
                    fixture.catalog.changed(new AudioCatalog.Changed(changed));
                    start=System.nanoTime();before=allocated();fixture.catalog.rebuild();
                    assertThat(fixture.catalog.current().songs()).hasSize(total);
                    assertThat(fixture.catalog.current().byId().get("000000")).isSameAs(index.byId().get("000000"));
                    verify(songs).byIds(List.of(changed));verify(songs,never()).batchAfter(anyString(),any(),anyInt());
                    verify(mapper).jobsForSongs(argThat(ids->ids.size()==1));verify(mapper).rolesForSongs(List.of(changed));
                    System.out.printf(Locale.ROOT,"AUDIO_INCREMENTAL_SYNTHETIC songs=%d changed=1 bulk_queries=3 elapsed_ms=%d allocated_bytes=%d%n",
                            total,(System.nanoTime()-start)/1_000_000,allocated()-before);
                } finally {processing.close();}
            }
        }
    }

    private static long allocated() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported())
            return allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        return 0;
    }
    class Fixture implements AutoCloseable {
        final AudioCatalog catalog;
        final AudioJobService jobs;
        Fixture(MediaSongRepository songs,AudioRoleMapper mapper) {
            var json=new ObjectMapper();
            var roles=new AudioRoleService(mapper,songs,json);
            jobs=new AudioJobService(mapper,roles,songs,mock(MediaPlaybackService.class),
                    mock(TranscodeClient.class),json,temp.toString());
            catalog=new AudioCatalog(songs,mapper,jobs);
        }
        public void close(){catalog.close();}
    }
}
