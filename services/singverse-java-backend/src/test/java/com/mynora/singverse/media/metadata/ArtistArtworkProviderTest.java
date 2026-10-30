package com.mynora.singverse.media.metadata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ArtistArtworkProviderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exactNameAndLibrarySongIdentifyArtistButAlbumArtIsNotUsed() throws Exception {
        var rows =
                json.readTree(
                        "[{\"name\":\"晴天\",\"ar\":[{\"id\":1,\"name\":\" 周杰伦"
                            + " \"}],\"al\":{\"picUrl\":\"album.jpg\"}}]");
        assertThat(ArtistArtworkProvider.matchedIds(rows, MusicProvider.NETEASE, "周杰伦", "晴天"))
                .containsExactly("1");
        assertThat(ArtistArtworkProvider.matchedIds(rows, MusicProvider.NETEASE, "周杰伦", "夜曲"))
                .isEmpty();
        assertThat(ArtistArtworkProvider.matchedIds(rows, MusicProvider.NETEASE, "周杰倫", "晴天"))
                .isEmpty();
    }

    @Test
    void homonymousArtistsRemainAmbiguous() throws Exception {
        var rows =
                json.readTree(
                        "[{\"name\":\"歌曲\",\"artists\":[{\"id\":1,\"name\":\"歌手\"}]},{\"name\":\"歌曲\",\"artists\":[{\"id\":2,\"name\":\"歌手\"}]}]");
        assertThat(ArtistArtworkProvider.matchedIds(rows, MusicProvider.NETEASE, "歌手", "歌曲"))
                .containsExactly("1", "2");
    }

    @Test
    void qqArtistMidAndUnicodeNormalizationAreSupported() throws Exception {
        var rows =
                json.readTree(
                        "[{\"songname\":\"Ｓｏｎｇ\",\"singer\":[{\"mid\":\"singer1\",\"name\":\"Artist\"}]}]");
        assertThat(ArtistArtworkProvider.matchedIds(rows, MusicProvider.QQ, "artist", "Song"))
                .containsExactly("singer1");
    }

    @Test
    void arbitraryAndPrivateImageAddressesAreRejectedBeforeDownload() {
        assertThatThrownBy(
                        () ->
                                com.mynora.singverse.media.artist.ArtistArtworkWorker.download(
                                        "https://127.0.0.1/image.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                com.mynora.singverse.media.artist.ArtistArtworkWorker.download(
                                        "http://p1.music.126.net/image.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void conflictingConfirmedPlatformIdsDoNotTriggerNameGuessing(){
        var provider=new ArtistArtworkProvider(json,new ProviderCallGuard());
        assertThat(provider.find("同名歌手",java.util.List.of("歌曲"),java.util.List.of(new ExternalTrack.ArtistRef(MusicProvider.NETEASE,"1","同名歌手"),new ExternalTrack.ArtistRef(MusicProvider.NETEASE,"2","同名歌手")))).isNull();
    }
}
