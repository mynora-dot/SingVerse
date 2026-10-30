package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.artist.ArtistService;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class ArtistArtworkProvider {
    public record Artwork(String provider, String externalId, String portrait, String background) {}

    private final ObjectMapper json;
    private final ProviderCallGuard guard;

    public ArtistArtworkProvider(ObjectMapper json, ProviderCallGuard guard) {
        this.json = json;
        this.guard = guard;
    }

    private static String canonical(String s) {
        return ArtistService.name(s).toLowerCase(Locale.ROOT);
    }

    private long nextStart;

    private synchronized <T> T call(MusicProvider p, java.util.function.Supplier<T> action) {
        long wait = nextStart - System.nanoTime();
        if (wait > 0)
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        nextStart = System.nanoTime() + 1_500_000_000L;
        return guard.call(p, action);
    }

    private static class AmbiguousArtist extends RuntimeException {}

    public Artwork find(String name, List<String> titles, List<ExternalTrack.ArtistRef> refs) {
        RuntimeException failure = null;
        for (var provider : List.of(MusicProvider.NETEASE, MusicProvider.QQ)) {
            try {
                var ids =
                        refs.stream()
                                .filter(
                                        r ->
                                                r.provider() == provider
                                                        && canonical(r.name())
                                                                .equals(canonical(name)))
                                .map(ExternalTrack.ArtistRef::id)
                                .distinct()
                                .toList();
                if(ids.size()>1)return null;
                if (ids.size() == 1) {
                    var http =
                            new MusicSourceHttp(
                                    json,
                                    provider,
                                    provider == MusicProvider.NETEASE
                                            ? Set.of("music.163.com", "interfacepc.music.163.com")
                                            : Set.of("c.y.qq.com"));
                    var direct = artwork(provider, ids.getFirst(), name, http);
                    if (direct != null) return direct;
                }
                var result = lookup(provider, name, titles);
                if (result != null) return result;
            } catch (AmbiguousArtist e) {
                return null;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        return null;
    }

    private Artwork lookup(MusicProvider p, String name, List<String> titles) {
        var http =
                new MusicSourceHttp(
                        json,
                        p,
                        p == MusicProvider.NETEASE
                                ? Set.of("music.163.com", "interfacepc.music.163.com")
                                : Set.of("c.y.qq.com"));
        var candidates = new LinkedHashSet<String>();
        for (String title : titles.stream().limit(3).toList()) {
            JsonNode root =
                    call(
                            p,
                            () -> {
                                if (p == MusicProvider.QQ)
                                    return http.get(
                                            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"
                                                    + MusicSourceHttp.query(
                                                            Map.of(
                                                                    "format",
                                                                    "json",
                                                                    "w",
                                                                    name + " " + title,
                                                                    "n",
                                                                    20,
                                                                    "p",
                                                                    1)),
                                            Map.of(),
                                            Duration.ofSeconds(8));
                                ObjectNode data =
                                        json.createObjectNode()
                                                .put("s", name + " " + title)
                                                .put("type", 1)
                                                .put("limit", 20)
                                                .put("offset", 0)
                                                .put("total", true);
                                data.putObject("header").put("os", "pc").put("appver", "3.1.0");
                                return http.form(
                                        "https://interfacepc.music.163.com/eapi/cloudsearch/pc",
                                        Map.of(
                                                "params",
                                                NeteaseCrypto.eapi(
                                                        "/api/cloudsearch/pc", data.toString())),
                                        Map.of("Referer", "https://music.163.com/"),
                                        Duration.ofSeconds(8));
                            });
            if (root.path("code").asInt(-1) != (p == MusicProvider.NETEASE ? 200 : 0))
                throw new MusicSourceException(p, "歌手图片搜索不可用");
            JsonNode rows =
                    p == MusicProvider.NETEASE
                            ? root.path("result").path("songs")
                            : root.path("data").path("song").path("list");
            candidates.addAll(matchedIds(rows, p, name, title));
        }
        if(candidates.size()>1)throw new AmbiguousArtist();
        if (candidates.size() != 1) return null;
        String id = candidates.iterator().next();
        return artwork(p, id, name, http);
    }

    static Set<String> matchedIds(JsonNode rows, MusicProvider p, String name, String title) {
        var candidates = new LinkedHashSet<String>();
        for (JsonNode row : rows) {
            String songName = row.path(p == MusicProvider.NETEASE ? "name" : "songname").asText();
            if (!canonical(songName).equals(canonical(title))) continue;
            JsonNode names =
                    p == MusicProvider.NETEASE
                            ? (row.has("ar") ? row.path("ar") : row.path("artists"))
                            : row.path("singer");
            for (JsonNode singer : names)
                if (canonical(singer.path("name").asText()).equals(canonical(name))) {
                    String id = singer.path(p == MusicProvider.NETEASE ? "id" : "mid").asText();
                    if (!id.isBlank()) candidates.add(id);
                }
        }
        return candidates;
    }

    private Artwork artwork(MusicProvider p, String id, String name, MusicSourceHttp http) {
        if (p == MusicProvider.QQ) {
            if (!id.matches("[A-Za-z0-9]{1,120}")) return null;
            return new Artwork(
                    "QQ",
                    id,
                    "https://y.gtimg.cn/music/photo_new/T001R800x800M000" + id + ".jpg",
                    null);
        }
        if (!id.matches("[0-9]{1,20}")) return null;
        var detail =
                call(
                        p,
                        () -> {
                            var payload = NeteaseCrypto.weapi("{}");
                            return http.form(
                                    "https://music.163.com/weapi/v1/artist/" + id,
                                    Map.of(
                                            "params",
                                            payload.params(),
                                            "encSecKey",
                                            payload.encSecKey()),
                                    Map.of("Referer", "https://music.163.com/"),
                                    Duration.ofSeconds(8));
                        });
        if (detail.path("code").asInt(-1) != 200) throw new MusicSourceException(p, "歌手详情不可用");
        JsonNode artist = detail.path("artist");
        if (!canonical(artist.path("name").asText()).equals(canonical(name))) return null;
        String portrait = artist.path("img1v1Url").asText("");
        if (portrait.isBlank()) portrait = artist.path("picUrl").asText("");
        if (portrait.isBlank() || portrait.contains("5639395138885805")) return null;
        return new Artwork("NETEASE", id, portrait, null);
    }
}
