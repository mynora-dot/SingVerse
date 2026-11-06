package com.mynora.singverse.media.missingfeedback;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/missing-song-feedback")
public class MissingFeedbackController {
    private final MissingFeedbackService service;

    public MissingFeedbackController(MissingFeedbackService service) { this.service = service; }

    @GetMapping
    public MissingFeedbackService.Page list(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(q, status, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MissingFeedbackService.Feedback create(@RequestBody MissingFeedbackService.Create input) { return service.create(input); }

    @GetMapping("/{id}")
    public MissingFeedbackService.Feedback get(@PathVariable String id) { return service.get(id); }

    @PutMapping("/{id}")
    public MissingFeedbackService.Feedback update(@PathVariable String id, @RequestBody MissingFeedbackService.Update input) { return service.update(id, input); }
}
