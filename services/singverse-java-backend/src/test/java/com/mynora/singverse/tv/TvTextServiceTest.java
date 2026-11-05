package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.webdav.metadata.WebDavMetadataMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TvTextServiceTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final MediaSongRepository songs = mock(MediaSongRepository.class);
    private final MediaSourceRepository sources = mock(MediaSourceRepository.class);
    private final WebDavMetadataMapper embedded = mock(WebDavMetadataMapper.class);
    private final MediaSongMapper storedSongs = mock(MediaSongMapper.class);
    private TvTextService service;

    @BeforeEach void setUp() {
        when(songs.get("song")).thenReturn(new Song("song", "source", "source", "sample.mp3",
                100L, null, null, "profile", 1, null, null, null, null, "fingerprint"));
        when(sources.get("source")).thenReturn(new Source("source", "source", "LOCAL",
                root.toString(), "", "profile", false, null, null, false, "", null, null, null, null));
        when(embedded.resource(anyMap())).thenReturn(List.of());
        when(storedSongs.find("song")).thenReturn(List.of());
        service = new TvTextService(songs, sources, mock(AuthCrypto.class), embedded, storedSongs, json);
    }

    @Test void returnsCachedEmbeddedLrcWhenThereIsNoSidecar() throws Exception {
        cached(Map.of("timing", "lrc", "text", "[00:02.00]真实歌词\n[00:04.50]下一句"));

        var result = service.tracks("song");

        assertThat(items(result)).containsExactly(Map.of("id", "metadata-lyrics", "format", "lrc",
                "text", "[00:02.00]真实歌词\n[00:04.50]下一句"));
        assertThat(result.get("warnings")).isEqualTo(List.of());
    }

    @Test void convertsOnlyActualMillisecondTimestampsAndKeepsUntimedLyricsUntimed() throws Exception {
        cached(Map.of("timing", "milliseconds", "lines", List.of(
                Map.of("time", 1234, "text", "第一句"), Map.of("time", 62345, "text", "第二句"))));
        assertThat(items(service.tracks("song"))).containsExactly(Map.of("id", "metadata-lyrics",
                "format", "lrc", "text", "[00:01.234]第一句" + System.lineSeparator()
                        + "[01:02.345]第二句" + System.lineSeparator()));

        for (String timing : List.of("none", "mpeg-frames")) {
            cached(Map.of("timing", timing, "text", "第一句\n第二句", "lines",
                    List.of(Map.of("time", 50, "text", "第一句"))));
            assertThat(items(service.tracks("song"))).containsExactly(Map.of("id", "metadata-lyrics",
                    "format", "text", "text", "第一句\n第二句"));
        }
    }

    @Test void recognizesRealLrcInsideUnsynchronizedId3Text() throws Exception {
        cached(Map.of("timing", "none", "text", "[ar:歌手]\n[00:02.000]真实时间"));
        assertThat(items(service.tracks("song")).getFirst()).containsEntry("format", "lrc");
    }

    @Test void readsLocallyScannedEmbeddedLyricsWithoutRequestingCompute() throws Exception {
        when(storedSongs.find("song")).thenReturn(List.of(Map.of("embedded_tags_json",
                json.writeValueAsString(Map.of("title", "标题", "lyrics",
                        Map.of("timing", "none", "text", "本地保存歌词\n第二句"))))));

        assertThat(items(service.tracks("song"))).containsExactly(Map.of("id", "metadata-lyrics",
                "format", "text", "text", "本地保存歌词\n第二句"));
    }

    @Test void keepsOriginalLrcAuthoritativeAndAppendsMetadataAfterOtherSidecars() throws Exception {
        cached(Map.of("timing", "lrc", "text", "[00:02]缓存歌词"));
        Files.writeString(root.resolve("sample.lrc"), "[00:01]原始歌词");
        Files.writeString(root.resolve("sample.srt"), "1\n00:00:01,000 --> 00:00:02,000\n原始字幕");
        Files.writeString(root.resolve("sample.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\n字幕");
        assertThat(items(service.tracks("song"))).extracting(item -> item.get("id"))
                .containsExactly("lrc", "srt", "vtt");
        verifyNoInteractions(embedded, storedSongs);

        Files.delete(root.resolve("sample.lrc"));
        assertThat(items(service.tracks("song"))).extracting(item -> item.get("id"))
                .containsExactly("srt", "vtt", "metadata-lyrics");
    }

    @Test void rejectsStaleOrArchivedCacheInsteadOfShowingLyricsFromReplacedMedia() throws Exception {
        String result = json.writeValueAsString(Map.of("lyrics", Map.of("text", "旧歌词")));
        for (var state : List.of(Map.of("changed", 1), Map.of("archived_at", "2026-11-05T00:00:00Z"))) {
            var row = new HashMap<String, Object>(state);
            row.put("result_json", result);
            when(embedded.resource(anyMap())).thenReturn(List.of(row));
            var tracks = service.tracks("song");
            assertThat(items(tracks)).isEmpty();
            assertThat(tracks.get("warnings")).isEqualTo(List.of("媒体已变化，等待重新读取元数据歌词"));
        }
    }

    @Test void unusableLrcDoesNotHideRealCachedLyricsOrExceedTheThreeTrackLimit() throws Exception {
        cached(Map.of("timing", "lrc", "text", "[00:02]真实缓存歌词"));
        Files.writeString(root.resolve("sample.srt"), "1\n00:00:01,000 --> 00:00:02,000\n字幕");
        Files.writeString(root.resolve("sample.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\n字幕");
        for (String broken : List.of("", "[ar:歌手]\n[ti:歌名]", "[00:02.00]   ", "[00:99.00]错误时间", "纯文本")) {
            Files.writeString(root.resolve("sample.lrc"), broken);
            var result = service.tracks("song");
            assertThat(items(result)).extracting(item -> item.get("id"))
                    .containsExactly("srt", "vtt", "metadata-lyrics");
            assertThat((List<?>) result.get("warnings")).hasSize(1);
        }
    }

    @Test void boundsUtf8CacheAndReportsInvalidSynchronizationWithoutInventingTime() throws Exception {
        cached(Map.of("timing", "none", "text", "歌".repeat(50000)));
        var oversized = service.tracks("song");
        assertThat(items(oversized)).isEmpty();
        assertThat((List<?>) oversized.get("warnings")).hasSize(1);

        cached(Map.of("timing", "milliseconds", "lines", List.of(Map.of("time", -1, "text", "错误"))));
        var invalid = service.tracks("song");
        assertThat(items(invalid)).isEmpty();
        assertThat((List<?>) invalid.get("warnings")).hasSize(1);
    }

    @Test void absentLyricsRemainAnOrdinaryEmptyResult() {
        var result = service.tracks("song");
        assertThat(items(result)).isEmpty();
        assertThat(result.get("warnings")).isEqualTo(List.of());
    }

    private void cached(Map<String, Object> lyrics) throws Exception {
        when(embedded.resource(anyMap())).thenReturn(List.of(Map.of("changed", 0,
                "result_json", json.writeValueAsString(Map.of("lyrics", lyrics)))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> items(Map<String, Object> result) {
        return (List<Map<String, String>>) result.get("items");
    }
}
