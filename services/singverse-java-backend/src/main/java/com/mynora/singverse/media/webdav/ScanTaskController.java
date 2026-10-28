package com.mynora.singverse.media.webdav;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media-sources/{id}/scan-tasks")
public class ScanTaskController {
    private final WebDavScanService service;
    public ScanTaskController(WebDavScanService service) { this.service = service; }
    @PostMapping("") public ResponseEntity<ScanTask> create(@PathVariable("id") String sourceId) {
        ScanTask task = service.create(sourceId);
        return ResponseEntity.accepted().location(URI.create("/api/v1/media-sources/" + sourceId + "/scan-tasks/" + task.id())).body(task);
    }
    @GetMapping("/latest") public ResponseEntity<ScanTask> latest(@PathVariable("id") String sourceId) {
        ScanTask task = service.latest(sourceId);
        return task == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(task);
    }
    @GetMapping("/{taskId}") public ScanTask get(@PathVariable("id") String sourceId, @PathVariable String taskId) { return service.get(sourceId, taskId); }
    @PostMapping("/{taskId}/cancel") public ScanTask cancel(@PathVariable("id") String sourceId, @PathVariable String taskId) { return service.cancel(sourceId, taskId); }
}
