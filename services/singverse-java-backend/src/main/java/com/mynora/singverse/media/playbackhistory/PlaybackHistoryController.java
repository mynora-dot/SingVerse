package com.mynora.singverse.media.playbackhistory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/playback-history")
public class PlaybackHistoryController {
    private final PlaybackHistoryService service;

    public PlaybackHistoryController(PlaybackHistoryService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> history(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(defaultValue = "") String songId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.history(q, period, songId, page, size);
    }

    @GetMapping("/popular")
    public Map<String, Object> popular(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(defaultValue = "") String songId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.popular(q, period, songId, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30d") String period,
            @RequestParam(defaultValue = "") String songId) {
        return service.summary(q, period, songId);
    }
}
