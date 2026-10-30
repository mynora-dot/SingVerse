package com.mynora.singverse.media.transcoding;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/transcoding")
public class TranscodeController {
    private final TranscodeService service;

    public TranscodeController(TranscodeService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Object capabilities() {
        return service.capabilities();
    }

    @GetMapping("/hardware-settings")
    public Object hardwareSettings() {
        return service.hardwareSettings();
    }

    @PostMapping("/hardware-settings")
    public Object saveHardware(@RequestBody JsonNode body) {
        return service.hardwareSettings(body, false);
    }

    @PostMapping("/hardware-settings/test")
    public Object testHardware(@RequestBody JsonNode body) {
        return service.hardwareSettings(body, true);
    }

    @PostMapping("/playback/{song}")
    public Object playback(@PathVariable String song, @RequestBody JsonNode body) {
        return service.negotiate(
                song,
                "admin",
                body,
                "/api/v1/media-songs/" + song + "/video",
                "/api/v1/transcoding/sessions");
    }

    @GetMapping("/sessions/{id}")
    public Object status(@PathVariable String id) {
        return service.view(id, "admin", "/api/v1/transcoding/sessions");
    }

    @PostMapping("/sessions/{id}/cancel")
    public void cancel(@PathVariable String id) {
        service.cancel(id, "admin");
    }

    @GetMapping("/sessions/{id}/artifacts/{name}")
    public void artifact(
            @PathVariable String id,
            @PathVariable String name,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletResponse response)
            throws IOException {
        service.artifact(id, "admin", name, response, range);
    }

    @GetMapping("/sessions/versions/{id}/{song}")
    public void version(
            @PathVariable String id,
            @PathVariable String song,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        service.version(id, song, response, range, "HEAD".equals(request.getMethod()));
    }

    @GetMapping("/offline")
    public Object tasks() {
        return service.tasks();
    }

    @PostMapping("/offline")
    public Object offline(@RequestBody JsonNode body) {
        return service.offline(body);
    }

    @PostMapping("/offline/{id}/cancel")
    public void cancelOffline(@PathVariable String id) {
        service.cancel(id, "admin");
    }

    @DeleteMapping("/offline/{id}")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }
}
