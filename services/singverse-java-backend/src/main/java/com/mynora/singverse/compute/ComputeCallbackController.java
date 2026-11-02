package com.mynora.singverse.compute;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ComputeCallbackController {
    private final ComputeCallback callback;
    public ComputeCallbackController(ComputeCallback callback) { this.callback = callback; }
    @GetMapping("/api/v1/internal/compute-callback/{id}")
    public ResponseEntity<String> check(@PathVariable String id,
            @RequestHeader(value="Authorization", required=false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_PLAIN).body(callback.verify(id, token));
    }
}
