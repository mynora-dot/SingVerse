package com.mynora.singverse.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.tv.TvLibrary;
import com.mynora.singverse.tv.TvService;
import com.mynora.singverse.tv.TvTextService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Administrator-authenticated, read-only projection. Never creates a TV participant or queue. */
@RestController
@RequestMapping("/api/v1/mobile/library/songs")
public class MobileLibraryController {
    private static final Set<String> AUDIO = Set.of(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "oga", "opus", "wma", "aif", "aiff", "ape");
    private final TvLibrary library;
    private final TvService projection;
    private final TvTextService text;
    private final MediaSongRepository songs;

    public MobileLibraryController(TvLibrary library, TvService projection,
            TvTextService text, MediaSongRepository songs) {
        this.library = library;
        this.projection = projection;
        this.text = text;
        this.songs = songs;
    }

    @GetMapping("")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> tagIds,
            @RequestParam(defaultValue = "false") boolean unclassified,
            @RequestParam(required = false) String artistId) {
        var result = new LinkedHashMap<>(library.search(q, page, size,
                tagIds == null ? List.of() : tagIds, unclassified, artistId));
        result.put("items", ((List<?>) result.get("items")).stream()
                .map(item -> detail(((JsonNode) item).path("id").asText())).toList());
        return result;
    }

    @GetMapping("/{id}")
    public ObjectNode detail(@PathVariable String id) {
        var song = songs.get(id);
        var out = projection.projection(song);
        String extension = Objects.toString(song.result().extension(), "").toLowerCase(Locale.ROOT);
        out.put("format", extension.toUpperCase(Locale.ROOT));
        out.put("mediaType", extension.isEmpty() ? "UNKNOWN" : AUDIO.contains(extension) ? "AUDIO" : "VIDEO");
        out.put("language", song.result().language().name());
        if (out.hasNonNull("coverPath"))
            out.put("coverPath", "/api/v1/metadata/songs/" + id + "/cover");
        return out;
    }

    @GetMapping("/{id}/text-tracks")
    public Map<String, Object> textTracks(@PathVariable String id) {
        return text.tracks(id);
    }
}
