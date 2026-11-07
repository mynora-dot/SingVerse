package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.media.artist.ArtistService;
import com.mynora.singverse.media.classification.ClassificationMapper;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TvLibraryScaleTest {
    static class CountedSong extends ObjectNode {
        static int copies;
        CountedSong(String id){super(JsonNodeFactory.instance);put("id",id);put("title","歌曲");}
        @Override public ObjectNode deepCopy(){copies++;return super.deepCopy();}
    }
    @Test void largeLibraryPagesCopyOnlyReturnedSongsAndByIdUsesItsPublishedMap() {
        for(int total:List.of(10_000,100_000)) {
            var mapper=mock(TvMapper.class);var tags=mock(ClassificationMapper.class);
            var library=new TvLibrary(mapper,mock(MediaSongRepository.class),mock(TvService.class),tags,
                    mock(ArtistService.class),new com.fasterxml.jackson.databind.ObjectMapper());
            var entries=new ArrayList<TvLibrary.Entry>();var byId=new HashMap<String,ObjectNode>();
            for(int index=0;index<total;index++) {
                String id=Integer.toString(index);var song=new CountedSong(id);
                entries.add(new TvLibrary.Entry(song,"歌曲",""));byId.put(id,song);
            }
            ReflectionTestUtils.setField(library,"index",new TvLibrary.Index(List.copyOf(entries),Map.copyOf(byId),
                    Map.of(),Map.of(),Map.of(),System.nanoTime(),0));
            CountedSong.copies=0;long start=System.nanoTime(),before=allocated();
            var page=library.search("",0,20,List.of(),false,null);
            long elapsed=(System.nanoTime()-start)/1_000_000,bytes=allocated()-before;
            assertThat(page.get("totalElements")).isEqualTo(total);
            assertThat((List<?>)page.get("items")).hasSize(20);
            assertThat(CountedSong.copies).isEqualTo(20);
            var requested=library.byIds(List.of("9","2","removed"));
            assertThat(requested).extracting(node->node.path("id").asText()).containsExactly("9","2");
            assertThat(CountedSong.copies).isEqualTo(22);
            requested.getFirst().put("title","客户端修改");
            assertThat(byId.get("9").path("title").asText()).isEqualTo("歌曲");
            verify(mapper,never()).library();
            System.out.printf(Locale.ROOT,"TV_PAGE_SYNTHETIC songs=%d returned=20 copies=20 elapsed_ms=%d allocated_bytes=%d%n",
                    total,elapsed,bytes);
        }
    }
    private static long allocated() {
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()
                ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
    }
}
