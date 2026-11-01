package com.mynora.singverse.media.playlist;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/playlists")
public class PlaylistController {
    private final PlaylistService service;

    public PlaylistController(PlaylistService service) {
        this.service = service;
    }

    @GetMapping("")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "UPDATED_DESC") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = service.list(q, sort, page, size);
        for (Object item : (List<?>) result.get("items")) admin((ObjectNode) item);
        return result;
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectNode create(@RequestBody PlaylistService.Save input) {
        return admin(service.save(null, input));
    }

    @GetMapping("/{id}")
    public ObjectNode get(@PathVariable String id) {
        return admin(service.get(id));
    }

    @PutMapping("/{id}")
    public ObjectNode save(@PathVariable String id, @RequestBody PlaylistService.Save input) {
        return admin(service.save(id, input));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id, @RequestParam long revision) {
        service.remove(id, revision);
    }

    @PostMapping("/generate")
    public ObjectNode generate(@RequestBody PlaylistService.Generate input) {
        return admin(service.generate(input));
    }

    private ObjectNode admin(ObjectNode node) {
        adminImages(node);
        return node;
    }

    private void adminImages(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject() && node.path("coverPath").isTextual())
            ((ObjectNode) node)
                    .put(
                            "coverPath",
                            node.path("coverPath")
                                    .asText()
                                    .replace(
                                            "/api/v1/tv/library/songs/",
                                            "/api/v1/metadata/songs/"));
        node.forEach(this::adminImages);
    }
}
