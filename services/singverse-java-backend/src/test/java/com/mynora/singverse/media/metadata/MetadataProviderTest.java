// Adapted fixture assertions from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class MetadataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void parsesQqSearchWithoutExposingPlayFields() throws Exception {
        JsonNode songs = mapper.readTree("""
                [{"songmid":"0039MnYb0qxYhV","songname":"晴天","singer":[{"name":"周杰伦"}],
                  "albumname":"叶惠美","albummid":"000MkMni19ClKG","interval":269,"pubtime":1059667200,
                  "playUrl":"https://forbidden.example/audio.m4a"}]
                """);
        QqMusicMetadataProvider provider = new QqMusicMetadataProvider(mapper, new ProviderCallGuard());

        ExternalTrack track = provider.parseSongs(songs, 20).getFirst();

        assertThat(track.provider()).isEqualTo(MusicProvider.QQ);
        assertThat(track.title()).isEqualTo("晴天");
        assertThat(track.artists()).containsExactly("周杰伦");
        assertThat(track.durationMs()).isEqualTo(269_000);
        assertThat(track.coverUrl()).startsWith("https://y.gtimg.cn/");
        assertThat(mapper.writeValueAsString(track)).doesNotContain("playUrl", "audio.m4a");
    }

    @Test
    void parsesNeteaseMultiArtistAndAliases() throws Exception {
        JsonNode songs = mapper.readTree("""
                [{"id":186016,"name":"晴天","ar":[{"name":"周杰伦"},{"name":"嘉宾"}],
                  "al":{"name":"叶惠美","picUrl":"http://p1.music.126.net/example.jpg"},
                  "dt":269000,"publishTime":1059667200000,"alia":["Sunny Day"],"fee":0,
                  "url":"https://forbidden.example/song.mp3"}]
                """);
        NeteaseMusicMetadataProvider provider = new NeteaseMusicMetadataProvider(mapper, new ProviderCallGuard());

        ExternalTrack track = provider.parseSongs(songs, 20).getFirst();

        assertThat(track.externalId()).isEqualTo("186016");
        assertThat(track.artists()).containsExactly("周杰伦", "嘉宾");
        assertThat(track.aliases()).containsExactly("Sunny Day");
        assertThat(track.coverUrl()).startsWith("https://p1.music.126.net/");
        assertThat(mapper.writeValueAsString(track)).doesNotContain("song.mp3");
    }

    @Test
    void parsesKugouSearchAndNormalizesCover() throws Exception {
        JsonNode songs = mapper.readTree("""
                [{"FileHash":"ABC123","SongName":"海阔天空 (Live)","SingerName":"Beyond / 嘉宾",
                  "AlbumName":"现场版","Duration":300,"PublishDate":"1993-05-01",
                  "Image":"https://imge.kugou.com/stdmusic/{size}/cover.jpg",
                  "AudioCdn":"https://forbidden.example/play"}]
                """);
        KugouMusicMetadataProvider provider = new KugouMusicMetadataProvider(mapper, new ProviderCallGuard(), null);

        ExternalTrack track = provider.parseSongs(songs, 20).getFirst();

        assertThat(track.externalId()).isEqualTo("ABC123");
        assertThat(track.artists()).containsExactly("Beyond", "嘉宾");
        assertThat(track.durationMs()).isEqualTo(300_000);
        assertThat(track.coverUrl()).contains("/400/");
        assertThat(mapper.writeValueAsString(track)).doesNotContain("AudioCdn", "forbidden");
    }

    @Test
    void cryptoAndSignatureMatchPinnedReferenceVectors() {
        String eapi = NeteaseCrypto.eapi("/api/cloudsearch/pc", "{\"s\":\"周杰伦\",\"type\":1,\"limit\":20}");
        assertThat(eapi).isEqualTo("2B5D64177AA6460FBAA3DCB1285E28954BBB4F7556E09B0FB25750F12398BB5061D31369B8FFA4BFF59E3B4A9103C9EFC4A9F27F2EB9646649834AF9158B1E0F6214D022CF1E4AA0D9C7B54F972E49EB00569FA6B45AEE84C26CBE3D32628482C58102189617DBCA01E159498438F92FACE8A02AD1667087F299171729EE3EC4");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", "1005"); params.put("clienttime", "1722470400"); params.put("clientver", "20489");
        params.put("dfid", "-"); params.put("keyword", "周杰伦"); params.put("mid", "ABCDEF");
        params.put("page", "1"); params.put("pagesize", "20"); params.put("uuid", "-");
        assertThat(KugouMusicMetadataProvider.androidSignature(params, "")).isEqualTo("befbbd147840a7f273da4998d37080fd");

        NeteaseCrypto.WeapiPayload weapi = NeteaseCrypto.weapi("{\"c\":\"[{\\\"id\\\":186016}]\"}", "abcdefghijklmnop");
        assertThat(weapi.params()).isNotBlank();
        assertThat(weapi.encSecKey()).hasSize(256);
    }

}
