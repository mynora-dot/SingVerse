package com.mynora.singverse.media.artist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ArtistArtworkSources {
    public record Choice(String provider, String externalId, String mbid, String portrait, String background, String attribution, boolean verified) {
        public Choice(String provider, String externalId, String mbid, String portrait, String background, String attribution) {
            this(provider,externalId,mbid,portrait,background,attribution,true);
        }
    }
    private final ObjectMapper json;
    private final ArtistArtworkSettings settings;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private long nextMusicBrainz;
    private long nextAudioDb;
    private long nextFanart;

    public ArtistArtworkSources(ObjectMapper json, ArtistArtworkSettings settings) {
        this.json = json;
        this.settings = settings;
    }

    private static String query(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String canonical(String s) { return ArtistService.name(s).toLowerCase(Locale.ROOT); }
    private static String value(JsonNode node, String key) { return node.path(key).asText(""); }
    private static boolean uuid(String s) { return s.matches("[0-9a-fA-F-]{36}"); }

    private synchronized JsonNode get(String url, String host, String key) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !host.equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null)
            throw new IllegalArgumentException("图片来源地址无效");
        long now = System.nanoTime();
        long due = host.equals("musicbrainz.org") ? nextMusicBrainz : host.equals("www.theaudiodb.com") ? nextAudioDb : nextFanart;
        if (due > now) {
            try { Thread.sleep(Duration.ofNanos(due - now)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        long next = System.nanoTime() + (host.equals("musicbrainz.org") ? 1_100_000_000L : 2_000_000_000L);
        if (host.equals("musicbrainz.org")) nextMusicBrainz = next;
        else if (host.equals("www.theaudiodb.com")) nextAudioDb = next;
        else nextFanart = next;
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                    .header("User-Agent", "SingVerse/0.1 (https://github.com/mynora-dot/SingVerse)");
            if (key != null) builder.header("client-key", key);
            var response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() == 404) return json.createObjectNode();
                if (response.statusCode() != 200) throw new IllegalStateException(host + " HTTP " + response.statusCode());
                byte[] bytes = stream.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) throw new IllegalStateException("图片来源响应过大");
                return json.readTree(bytes);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("图片来源请求失败", e);
        }
    }

    public List<Choice> find(String name, List<String> titles) {
        var result = new ArrayList<Choice>();
        JsonNode direct = get("https://www.theaudiodb.com/api/v1/json/123/search.php?s=" + query(name), "www.theaudiodb.com", null).path("artists");
        for (JsonNode row : direct) if (canonical(value(row,"strArtist")).equals(canonical(name))) addAudioDb(result,row,titles);
        if (result.stream().noneMatch(Choice::verified)) {
            JsonNode matches = get("https://musicbrainz.org/ws/2/artist/?query=" + query("alias:\"" + name + "\"") + "&fmt=json&limit=10", "musicbrainz.org", null).path("artists");
            for (JsonNode row : matches) {
                String mbid = value(row,"id");
                if (!uuid(mbid) || !hasAlias(row,name) || !hasRecording(mbid,titles)) continue;
                JsonNode found = get("https://www.theaudiodb.com/api/v1/json/123/artist-mb.php?i=" + query(mbid), "www.theaudiodb.com", null).path("artists");
                int before = result.size();
                for (JsonNode artist : found) if (mbid.equalsIgnoreCase(value(artist,"strMusicBrainzID"))) addAudioDb(result,artist,titles);
                if (result.size() == before) result.add(new Choice("MUSICBRAINZ",mbid,mbid,"","","MusicBrainz"));
            }
        }
        return result.stream().distinct().toList();
    }

    private boolean hasRecording(String mbid, List<String> titles) {
        for (String title : titles.stream().limit(3).toList()) {
            JsonNode rows = get("https://musicbrainz.org/ws/2/recording/?query=" + query("recording:\"" + title + "\" AND arid:" + mbid) + "&fmt=json&limit=5", "musicbrainz.org", null).path("recordings");
            for (JsonNode row : rows) if (canonical(value(row,"title")).equals(canonical(title))) return true;
        }
        return false;
    }

    private static boolean hasAlias(JsonNode row, String name) {
        if (canonical(value(row,"name")).equals(canonical(name))) return true;
        for (JsonNode alias : row.path("aliases")) if (canonical(value(alias,"name")).equals(canonical(name))) return true;
        return false;
    }

    private void addAudioDb(List<Choice> result, JsonNode row, List<String> titles) {
        String mbid = value(row,"strMusicBrainzID");
        String id = value(row,"idArtist");
        if (!id.matches("[0-9]{1,20}")) return;
        boolean verified = uuid(mbid) && hasRecording(mbid,titles);
        result.add(new Choice("THEAUDIODB", id, mbid, value(row,"strArtistThumb"), value(row,"strArtistFanart"), "TheAudioDB",verified));
    }

    public Choice fanart(Choice identity) {
        String key = settings.key();
        if (key == null || key.isBlank() || !identity.verified() || !uuid(identity.mbid())) return null;
        JsonNode data = get("https://webservice.fanart.tv/v3.2/music/" + identity.mbid(), "webservice.fanart.tv", key);
        JsonNode backgrounds = data.path("artistbackground");
        for (JsonNode row : backgrounds) {
            String url = value(row,"url");
            if (!url.isBlank()) return new Choice("FANART", identity.mbid(), identity.mbid(), "", url, "fanart.tv");
        }
        return null;
    }
}
