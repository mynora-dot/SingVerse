package com.mynora.singverse.media.controller;

import com.mynora.singverse.media.dto.MediaSourceDtos.*;
import com.mynora.singverse.media.service.MediaSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/media-sources")
public class MediaSourceController {
    private final com.mynora.singverse.media.service.LocalDirectoryService directories;
    private final MediaSourceService service;
    private final com.mynora.singverse.media.service.MediaScanService scans;

    public MediaSourceController(MediaSourceService service, com.mynora.singverse.media.service.MediaScanService scans, com.mynora.singverse.media.service.LocalDirectoryService directories) {
        this.directories = directories;
        this.service = service; this.scans = scans;
    }

    @GetMapping("/local-directories")
    public com.mynora.singverse.media.service.LocalDirectoryService.DirectoryPage directories(
            @RequestParam(defaultValue = "") String path, @RequestParam(defaultValue = "0") int page) {
        return directories.list(path, page);
    }

    @GetMapping("")
    public Page list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String q,
            @RequestParam(required = false) String type, @RequestParam(required = false) String profileId) {
        return service.list(q, type, profileId, page, size);
    }

    @PostMapping("")
    public ResponseEntity<Source> create(@RequestBody Input input) {
        Source source = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/media-sources/" + source.id())).body(source);
    }

    @PutMapping("/{id}")
    public Source update(@PathVariable String id, @RequestBody Input input) {
        return service.update(id, input);
    }

    @GetMapping("/{id}")
    public Source get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/scan")
    public com.mynora.singverse.media.dto.MediaSongDtos.Scan scan(@PathVariable String id) {
        return scans.scan(id);
    }

    @GetMapping("/{id}/songs")
    public com.mynora.singverse.media.dto.MediaSongDtos.Page songs(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return scans.list(id, page, size);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
