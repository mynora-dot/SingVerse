package com.mynora.singverse.media.transcoding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transcoding/cache")
public class SongCacheController {
    private final SongCacheService service;

    public SongCacheController(SongCacheService service) {
        this.service = service;
    }

    @GetMapping("")
    public Object list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @PutMapping("/settings")
    public Object save(@RequestBody JsonNode body) {
        return service.save(body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @PostMapping("/clear")
    public Object clear() {
        return service.clear();
    }
}
