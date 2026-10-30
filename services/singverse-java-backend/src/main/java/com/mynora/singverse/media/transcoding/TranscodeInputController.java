package com.mynora.singverse.media.transcoding;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

@RestController
public class TranscodeInputController {
    private final TranscodeService service;

    public TranscodeInputController(TranscodeService service) {
        this.service = service;
    }

    @RequestMapping(
            value = "/api/v1/internal/transcode-input/{id}",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public void source(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletRequest request,
            HttpServletResponse response)
            throws java.io.IOException {
        service.source(id, token, range, "HEAD".equals(request.getMethod()), response);
    }
}
