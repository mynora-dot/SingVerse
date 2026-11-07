package com.mynora.singverse.media.playbackhistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.auth.security.AuthHttp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Both routes use the existing administrator login/CSRF; the route fixes the collector type. */
@RestController
public class PlaybackActivityController {
    private final PlaybackActivityService service;
    private final AuthHttp cookies;

    public PlaybackActivityController(PlaybackActivityService service, AuthHttp cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping({"/api/v1/playback-attempts", "/api/v1/mobile/playback-attempts"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(HttpServletRequest request, @RequestBody JsonNode body) {
        return service.create(type(request), owner(request), body.path("songId").asText(""),
                body.path("deviceName").asText(""), startedAt(body));
    }

    @PostMapping({"/api/v1/playback-attempts/{id}/events", "/api/v1/mobile/playback-attempts/{id}/events"})
    public Map<String, Object> event(HttpServletRequest request, @PathVariable String id, @RequestBody JsonNode body) {
        return service.report(type(request), owner(request), id, body);
    }

    private String type(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/mobile/") ? "MOBILE" : "ADMIN";
    }

    private Long startedAt(JsonNode body) {
        if (!body.has("startedAt")) return null;
        var value = body.path("startedAt");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "播放开始时间无效");
        return value.asLong();
    }

    private String owner(HttpServletRequest request) {
        String token = cookies.cookie(request, AuthHttp.SESSION);
        if (token == null || token.length() != 43) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return AuthCrypto.hash(token);
    }
}
