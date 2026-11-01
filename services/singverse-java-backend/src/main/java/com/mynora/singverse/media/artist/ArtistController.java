package com.mynora.singverse.media.artist;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.tv.TvLibrary;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/artists")
public class ArtistController {
    private final ArtistService artists;
    private final TvLibrary library;
    private final ArtistArtworkSettings artworkSettings;

    public ArtistController(ArtistService artists, TvLibrary library, ArtistArtworkSettings artworkSettings) {
        this.artists = artists;
        this.library = library;
        this.artworkSettings = artworkSettings;
    }

    @GetMapping("/artwork-settings")
    public Map<String, Boolean> artworkSettings() { return artworkSettings.view(); }

    public record ArtworkSettingsInput(String fanartKey, boolean clearFanartKey) {}

    @PutMapping("/artwork-settings")
    public Map<String, Boolean> saveArtworkSettings(@RequestBody ArtworkSettingsInput input) {
        return artworkSettings.save(input.fanartKey(), input.clearFanartKey());
    }

    @GetMapping("")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(required = false) Integer countLessThan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return library.managedArtists(q, type, countLessThan, page, size);
    }

    public record CategoryInput(String type, long revision) {}
    @PutMapping("/{id}/category") public ObjectNode category(@PathVariable String id, @RequestBody CategoryInput input) {
        if (input == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "歌手分类无效");
        return artists.category(id, input.type(), input.revision());
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode create(@RequestBody ArtistDtos.Save input) {
        return artists.save(null, input);
    }

    @GetMapping("/{id}")
    public ObjectNode get(@PathVariable String id) {
        var profile = artists.get(id);
        try {
            var songs = library.managedSongs(id, 0, 1);
            profile.put("count", ((Number) songs.get("totalElements")).longValue());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if(e.getStatusCode().value()!=404)throw e;
            profile.put("count",0);
        }
        return profile;
    }

    @PutMapping("/{id}")
    public ObjectNode update(@PathVariable String id, @RequestBody ArtistDtos.Save input) {
        return artists.save(id, input);
    }

    @GetMapping("/{id}/songs")
    public Map<String, Object> songs(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return library.adminArtistSongs(id, page, size);
    }

    @GetMapping("/credits")
    public Map<String, Object> credits(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "true") boolean unbound,
            @RequestParam(required = false) String artistId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return library.credits(q, unbound, artistId, page, size);
    }

    @PostMapping("/bindings")
    public Map<String, Boolean> bind(@RequestBody ArtistDtos.Bind input) {
        artists.bind(input);
        return Map.of("success", true);
    }

    @PostMapping("/{id}/images/retry")
    public Map<String, Boolean> retry(@PathVariable String id) {
        artists.retry(id);
        return Map.of("success", true);
    }

    @GetMapping("/{id}/images/{kind}/candidates")
    public java.util.List<ArtistArtworkSources.Choice> candidates(@PathVariable String id, @PathVariable String kind) {
        return artists.candidates(id, kind);
    }

    public record CandidateSelection(String provider, String externalId) {}

    @PostMapping("/{id}/images/{kind}/selection")
    public ObjectNode selectCandidate(@PathVariable String id, @PathVariable String kind, @RequestBody CandidateSelection selection) throws Exception {
        return artists.selectCandidate(id,kind,selection.externalId(),selection.provider());
    }

    @GetMapping("/{id}/images/{kind}/candidates/{provider}/{externalId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable String id,@PathVariable String kind,@PathVariable String provider,@PathVariable String externalId) throws Exception {
        return artists.candidatePreview(id,kind,externalId,provider);
    }

    @GetMapping("/song-overrides/{songId}")
    public ObjectNode override(@PathVariable String songId) {
        return artists.override(songId);
    }

    @DeleteMapping("/song-overrides/{songId}")
    public Map<String, Boolean> reset(@PathVariable String songId, @RequestParam long revision) {
        artists.reset(songId, revision);
        return Map.of("success", true);
    }

    @GetMapping("/{id}/images/{kind}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String kind) {
        return artists.image(id, kind, false);
    }

    @PutMapping(value = "/{id}/images/{kind}", consumes = "multipart/form-data")
    public ObjectNode upload(
            @PathVariable String id,
            @PathVariable String kind,
            @RequestParam(defaultValue = "") String version,
            @RequestParam MultipartFile file) {
        return artists.upload(id, kind, version, file);
    }

    @DeleteMapping("/{id}/images/{kind}")
    public ObjectNode remove(
            @PathVariable String id, @PathVariable String kind, @RequestParam String version) {
        return artists.removeImage(id, kind, version);
    }
}
