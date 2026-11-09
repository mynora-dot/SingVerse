package com.mynora.singverse.background;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/background-tasks")
public class BackgroundTaskController {
    private final BackgroundTaskService service;
    public BackgroundTaskController(BackgroundTaskService service) { this.service = service; }
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "ACTIVE") String view,
            @RequestParam(required = false) String type, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(view, type, status, q, page, size);
    }
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) { return service.detail(id); }
    @GetMapping("/{id}/children")
    public Map<String, Object> children(@PathVariable String id, @RequestParam(required = false) String type,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.children(id, type, status, page, size);
    }
    @GetMapping("/{id}/items")
    public Map<String, Object> items(@PathVariable String id, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.items(id, status, page, size);
    }
}
