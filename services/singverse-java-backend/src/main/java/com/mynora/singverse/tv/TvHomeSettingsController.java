package com.mynora.singverse.tv;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;

/** Admin cookie/CSRF chain applies to every endpoint in this controller. */
@RestController
@RequestMapping("/api/v1/admin/tv-settings")
public class TvHomeSettingsController {
    private final TvHomeSettingsService settings;
    public TvHomeSettingsController(TvHomeSettingsService settings) { this.settings = settings; }
    @GetMapping("") public TvHomeSettingsService.Settings read() { return settings.read(false); }
    @PutMapping("") public TvHomeSettingsService.Settings save(@RequestBody TvHomeSettingsService.Settings input) { return settings.save(input); }
    @PostMapping("/banners") public TvHomeSettingsService.Settings upload(@RequestParam long revision, @RequestParam MultipartFile file) { return settings.upload(revision, file); }
    @DeleteMapping("/banners/{id}") public TvHomeSettingsService.Settings remove(@PathVariable String id, @RequestParam long revision) { return settings.remove(id, revision); }
    @GetMapping("/banners/{id}/image") public ResponseEntity<byte[]> image(@PathVariable String id) { return settings.image(id, false); }
}
