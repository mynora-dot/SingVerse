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
    public void delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean confirmRecent) {
        service.delete(id, confirmRecent);
    }

    @PostMapping("/clear")
    public Object clear() {
        return service.clear();
    }

    @GetMapping("/pins")
    public Object pins(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return service.pins(page, size); }

    @PutMapping("/songs/{songId}/pin")
    public Object pin(@PathVariable String songId, @RequestBody JsonNode body) { return service.pin(songId, body); }

    @PostMapping("/cleanup-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object cleanup(@RequestBody JsonNode body) { return service.cleanupJob(body); }

    @GetMapping("/cleanup-jobs/{id}")
    public Object job(@PathVariable String id) { return service.job(id); }

    @PostMapping("/cleanup-jobs/{id}/cancel")
    public Object cancel(@PathVariable String id) { return service.cancelJob(id); }
}
