package com.mynora.singverse.tv;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.media.classification.ClassificationMapper;
import com.mynora.singverse.media.service.*;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("")
public class TvController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.mynora.singverse.media.playlist.PlaylistService playlists;

    @org.springframework.beans.factory.annotation.Autowired private TvLibrary library;

    @org.springframework.beans.factory.annotation.Autowired
    private com.mynora.singverse.media.artist.ArtistService artists;

    @org.springframework.beans.factory.annotation.Autowired
    private com.mynora.singverse.media.classification.ClassificationCoverService tagCovers;

    @org.springframework.beans.factory.annotation.Autowired private TvTextService textTracks;
    @org.springframework.beans.factory.annotation.Autowired private TvReleaseService releases;
    @org.springframework.beans.factory.annotation.Autowired private com.mynora.singverse.media.transcoding.TranscodeService transcoding;
    private final TvService service;
    private final MediaSongService songs;
    private final MediaPlaybackService playback;
    private final ClassificationMapper tags;
    private final ObjectMapper json;
    private final com.mynora.singverse.media.metadata.MetadataCoverService covers;

    public TvController(
            TvService service,
            MediaSongService songs,
            MediaPlaybackService playback,
            ClassificationMapper tags,
            ObjectMapper json,
            com.mynora.singverse.media.metadata.MetadataCoverService covers) {
        this.covers = covers;
        this.service = service;
        this.songs = songs;
        this.playback = playback;
        this.tags = tags;
        this.json = json;
    }

    ObjectNode actor(HttpServletRequest r) {
        String h = r.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw TvService.fail(401, "请先连接");
        return service.authenticate(h.substring(7));
    }

    @GetMapping("/api/v1/server-info")
    public Map<String, Object> info() {
        return service.serverInfo();
    }

    @PostMapping("/api/v1/device-authorizations")
    public ObjectNode authorize(@RequestBody JsonNode input) {
        return service.authorize(input);
    }

    @PostMapping("/api/v1/device-authorizations/{id}/poll")
    public ObjectNode poll(@PathVariable String id, @RequestBody JsonNode input) {
        return service.exchange(id, TvService.text(input, "pollSecret", 200));
    }

    @GetMapping("/api/v1/admin/tv-devices")
    public Map<String, Object> devices() {
        return Map.of(
                "devices",
                service.adminList("device"),
                "pending",
                service.adminList("authorization").stream()
                        .filter(n -> n.path("status").asText().equals("PENDING"))
                        .toList());
    }

    @PostMapping("/api/v1/admin/device-authorizations/{id}/approve")
    public ObjectNode approve(@PathVariable String id, @RequestBody JsonNode input) {
        return service.approve(id, input);
    }

    @DeleteMapping("/api/v1/admin/device-authorizations/{id}")
    public void deny(@PathVariable String id) {
        service.deny(id);
    }

    @DeleteMapping("/api/v1/admin/tv-devices/{id}")
    public void revoke(@PathVariable String id) {
        service.revoke(id);
    }

    @GetMapping({"/api/v1/tv/library/songs", "/api/v1/party/library/songs"})
    public Map<String, Object> songs(
            HttpServletRequest r,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> tagIds,
            @RequestParam(defaultValue = "false") boolean unclassified) {
        actor(r);
        return library.search(
                q == null ? "" : q,
                page,
                size,
                tagIds == null ? List.of() : tagIds,
                unclassified,
                null);
    }

    @GetMapping({
        "/api/v1/tv/library/classification-tags",
        "/api/v1/party/library/classification-tags"
    })
    public List<Map<String, Object>> tags(HttpServletRequest r) {
        actor(r);
        return tags.tags().stream()
                .filter(t -> ((Number) t.get("active")).intValue() == 1)
                .map(
                        t -> {
                            var out = new LinkedHashMap<String, Object>();
                            for (String k : List.of("id", "dimension", "name", "count", "revision"))
                                if (t.containsKey(k)) out.put(k, t.get(k));
                            out.put("count", t.getOrDefault("song_count", 0));
                            if (t.get("cover_version") != null) {
                                out.put("coverVersion", t.get("cover_version"));
                                out.put(
                                        "coverPath",
                                        "/api/v1/tv/library/classification-tags/"
                                                + t.get("id")
                                                + "/cover");
                            }
                            return (Map<String, Object>) out;
                        })
                .toList();
    }

    @GetMapping("/api/v1/tv/library/classification-tags/{id}/cover")
    public org.springframework.http.ResponseEntity<byte[]> tagCover(
            HttpServletRequest request, @PathVariable String id) {
        actor(request);
        return tagCovers.image(id, true);
    }

    private void device(HttpServletRequest request) {
        if (!actor(request).path("kind").asText().equals("device"))
            throw TvService.fail(403, "需要电视设备授权");
    }

    @GetMapping("/api/v1/tv/releases/current")
    public Map<String, Object> release(HttpServletRequest request) {
        device(request);
        return releases.current();
    }

    @GetMapping("/api/v1/tv/releases/apk")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> apk(
            HttpServletRequest request) throws java.io.IOException {
        device(request);
        var file = releases.apk();
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/vnd.android.package-archive"))
                .contentLength(java.nio.file.Files.size(file))
                .body(new org.springframework.core.io.FileSystemResource(file));
    }

    @GetMapping("/api/v1/tv/home")
    public Map<String, Object> home(HttpServletRequest request) {
        var identity = actor(request);
        if (!identity.path("kind").asText().equals("device")) throw TvService.fail(403, "仅电视可读取首页");
        library.invalidate();
        var recent = songs.list(null, null, "ALL", 0, 8);
        return Map.of(
                "server",
                service.serverInfo(),
                "totalSongs",
                recent.totalElements(),
                "recentSongs",
                recent.items().stream().map(service::projection).toList(),
                "generatedAt",
                java.time.Instant.now().toString());
    }

    @GetMapping("/api/v1/tv/library/songs/{id}")
    public ObjectNode detail(HttpServletRequest request, @PathVariable String id) {
        actor(request);
        return service.songDetail(id);
    }

    @GetMapping("/api/v1/tv/library/songs/{id}/text-tracks")
    public Map<String, Object> textTracks(HttpServletRequest request, @PathVariable String id) {
        var actor = actor(request);
        if (!actor.path("kind").asText().equals("device")) throw TvService.fail(403, "仅电视可读取演唱文本");
        return textTracks.tracks(id);
    }

    @GetMapping("/api/v1/tv/library/playlists")
    public Map<String, Object> playlists(
            HttpServletRequest r,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        actor(r);
        return playlists.list(q, page, size);
    }

    @GetMapping("/api/v1/tv/library/playlists/{id}")
    public ObjectNode playlist(HttpServletRequest r, @PathVariable String id) {
        actor(r);
        return playlists.get(id);
    }

    @GetMapping("/api/v1/tv/library/playlists/{id}/songs")
    public Map<String, Object> playlistSongs(
            HttpServletRequest r,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        actor(r);
        return playlists.songs(id, page, size);
    }

    @GetMapping("/api/v1/tv/library/artists")
    public Map<String, Object> artists(
            HttpServletRequest r,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        actor(r);
        return library.artists(q, page, size);
    }

    @GetMapping("/api/v1/tv/library/artists/{id}")
    public ObjectNode artistDetail(HttpServletRequest request, @PathVariable String id) {
        actor(request);
        return library.artistDetail(id);
    }

    @GetMapping("/api/v1/tv/library/artists/{id}/images/{kind}")
    public org.springframework.http.ResponseEntity<byte[]> artistImage(
            HttpServletRequest request, @PathVariable String id, @PathVariable String kind) {
        actor(request);
        return artists.image(id, kind, true);
    }

    @GetMapping("/api/v1/tv/library/artists/{id}/songs")
    public Map<String, Object> artistSongs(
            HttpServletRequest r,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        actor(r);
        return library.search("", page, size, List.of(), false, id);
    }

    @GetMapping("/api/v1/tv/library/songs/{id}/cover")
    public org.springframework.http.ResponseEntity<byte[]> cover(
            HttpServletRequest r, @PathVariable String id) {
        actor(r);
        var cover = covers.applied(id);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(cover.type())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(cover.bytes());
    }

    @PostMapping("/api/v1/tv/playback-resources")
    public Map<String, Object> resource(HttpServletRequest r, @RequestBody JsonNode input) {
        var authorizedUrl = service.resource(actor(r), TvService.text(input, "songId", 64));
        var a = actor(r);
        var resource = transcoding.negotiate(TvService.text(input, "songId", 64), "tv:"+a.path("id").asText(), input,
                authorizedUrl, "/api/v1/tv/transcoding");
        resource.put("revision", service.songDetail(TvService.text(input, "songId", 64)).path("revision").asText());
        return json.convertValue(resource, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
    }

    @RequestMapping(
            value = "/api/v1/tv/media/{id}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void media(
            HttpServletRequest r,
            HttpServletResponse response,
            @PathVariable String id,
            @RequestHeader(value = "Range", required = false) String range)
            throws java.io.IOException {
        service.resource(actor(r), id);
        playback.play(id, range, "HEAD".equals(r.getMethod()), response);
    }

    @GetMapping("/api/v1/tv/transcoding/{id}")
    public Object transcodeStatus(HttpServletRequest r, @PathVariable String id) {
        return transcoding.view(id, "tv:"+actor(r).path("id").asText(), "/api/v1/tv/transcoding");
    }
    @PostMapping("/api/v1/tv/transcoding/{id}/cancel")
    public void transcodeCancel(HttpServletRequest r, @PathVariable String id) {
        transcoding.cancel(id, "tv:"+actor(r).path("id").asText());
    }
    @GetMapping("/api/v1/tv/transcoding/{id}/artifacts/{name}")
    public void transcodeArtifact(HttpServletRequest r, HttpServletResponse response, @PathVariable String id,
            @PathVariable String name, @RequestHeader(value="Range",required=false) String range) throws java.io.IOException {
        transcoding.artifact(id, "tv:"+actor(r).path("id").asText(), name, response, range);
    }
    @GetMapping("/api/v1/tv/transcoding/versions/{id}/{song}")
    public void transcodeVersion(HttpServletRequest r, HttpServletResponse response, @PathVariable String id,
            @PathVariable String song, @RequestHeader(value="Range",required=false) String range) throws java.io.IOException {
        service.resource(actor(r), song);
        transcoding.version(id, song, response, range, "HEAD".equals(r.getMethod()));
    }

    @PostMapping("/api/v1/tv/sessions")
    public ObjectNode session(HttpServletRequest r) {
        var a = actor(r);
        var n = service.session(a);
        return service.snapshot(a, n.path("id").asText());
    }

    @PostMapping("/api/v1/tv/sessions/{id}/recover")
    public ObjectNode recover(HttpServletRequest r, @PathVariable String id) {
        return service.recover(actor(r), id);
    }

    @GetMapping({"/api/v1/tv/sessions/{id}/snapshot", "/api/v1/party/sessions/{id}/snapshot"})
    public ObjectNode snapshot(HttpServletRequest r, @PathVariable String id) {
        return service.snapshot(actor(r), id);
    }

    @PostMapping({"/api/v1/tv/sessions/{id}/commands", "/api/v1/party/sessions/{id}/commands"})
    public ObjectNode change(
            HttpServletRequest r, @PathVariable String id, @RequestBody JsonNode input) {
        return service.change(actor(r), id, input);
    }

    @PostMapping("/api/v1/tv/sessions/{id}/playback-events")
    public ObjectNode event(
            HttpServletRequest r, @PathVariable String id, @RequestBody JsonNode input) {
        return service.event(actor(r), id, input);
    }

    @PostMapping("/api/v1/tv/sessions/{id}/invitations")
    public ObjectNode invitation(HttpServletRequest r, @PathVariable String id) {
        return service.invitation(actor(r), id);
    }

    @GetMapping("/api/v1/tv/sessions/{id}/participants")
    public List<ObjectNode> participants(HttpServletRequest r, @PathVariable String id) {
        return service.participants(actor(r), id);
    }

    @PostMapping("/api/v1/party/invitations/preview")
    public ObjectNode preview(@RequestBody JsonNode input) {
        return service.preview(TvService.text(input, "invitation", 200));
    }

    @PostMapping("/api/v1/party/join")
    public ObjectNode join(@RequestBody JsonNode input) {
        return service.join(input);
    }
}
