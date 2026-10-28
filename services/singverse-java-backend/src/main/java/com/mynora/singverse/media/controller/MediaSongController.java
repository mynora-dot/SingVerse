package com.mynora.singverse.media.controller;

import com.mynora.singverse.media.dto.MediaSongDtos.Page;
import com.mynora.singverse.media.service.MediaSongService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media-songs")
public class MediaSongController {
    private final com.mynora.singverse.media.service.MediaPlaybackService playback;
    private final MediaSongService service;
    public MediaSongController(MediaSongService service, com.mynora.singverse.media.service.MediaPlaybackService playback) { this.service = service; this.playback = playback; }

    @RequestMapping(value = "/{id}/video", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void video(@PathVariable String id, @RequestHeader(value = "Range", required = false) String range,
            jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        playback.play(id, range, "HEAD".equals(request.getMethod()), response);
    }

    @GetMapping("")
    public Page list(@RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required=false) java.util.List<String> tagIds, @RequestParam(defaultValue="false") boolean unclassified) {
        return service.list(sourceId, q, status, page, size, tagIds==null?java.util.List.of():tagIds, unclassified);
    }
}
