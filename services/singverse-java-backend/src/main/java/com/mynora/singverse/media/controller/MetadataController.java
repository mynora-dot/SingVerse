package com.mynora.singverse.media.controller;

import com.mynora.singverse.media.metadata.*;
import static com.mynora.singverse.media.metadata.MetadataDtos.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/metadata")
public class MetadataController {
    private final MetadataSettingsService settings;
    private final MetadataService service;
    private final MetadataCoverService covers;
    private final SingleReviewService review;
    private final CoverAssetService assets;
    public MetadataController(MetadataSettingsService settings, MetadataService service, MetadataCoverService covers, SingleReviewService review, CoverAssetService assets) {
        this.review=review; this.assets=assets; this.settings = settings; this.service = service; this.covers = covers;
    }
    @GetMapping("/songs/{songId}/review") public SingleReviewDtos.Review review(@PathVariable String songId) { return review.review(songId); }
    @PostMapping("/songs/{songId}/review/apply") public SingleReviewDtos.Result reviewApply(@PathVariable String songId, @RequestBody SingleReviewDtos.Apply input) { return review.apply(songId,input); }
    @PostMapping("/songs/{songId}/covers/search") public SingleReviewDtos.Covers coverSearch(@PathVariable String songId, @RequestBody MetadataDtos.SearchInput input) { return review.covers(songId,input); }
    @PostMapping(value="/songs/{songId}/covers/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED) public SingleReviewDtos.Asset upload(@PathVariable String songId, @RequestParam String fingerprint, @RequestParam("file") org.springframework.web.multipart.MultipartFile file) { return assets.upload(songId,fingerprint,file); }
    @GetMapping("/cover-assets/{id}") public ResponseEntity<byte[]> assetImage(@PathVariable String id) { return image(assets.image(id)); }
    @GetMapping("/settings") public Settings settings() { return settings.read(); }
    @PutMapping("/settings") public Settings settings(@RequestBody Settings input) { return settings.save(input); }
    @PostMapping("/songs/{songId}/search") public SearchResult search(@PathVariable String songId, @RequestBody(required = false) SearchInput input) { return service.search(songId, input); }
    @PostMapping("/songs/{songId}/apply") public Applied apply(@PathVariable String songId, @RequestBody ApplyInput input) { return service.apply(songId, input); }
    @PostMapping("/batches") @ResponseStatus(HttpStatus.CREATED) public Batch start(@RequestBody StartInput input) { return service.start(input); }
    @GetMapping("/batches/latest") public ResponseEntity<Batch> latest() { var batch = service.latest(); return batch == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(batch); }
    @GetMapping("/batches/{id}") public Batch batch(@PathVariable String id, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required=false) String q, @RequestParam(defaultValue="DEFAULT") String sort) { return service.details(id, status, page, size, q, sort); }
    @PostMapping("/batches/{id}/pause") public Batch pause(@PathVariable String id) { return service.pause(id); }
    @PostMapping("/batches/{id}/resume") public Batch resume(@PathVariable String id) { return service.resume(id); }
    @PostMapping("/batches/{id}/cancel") public Batch cancel(@PathVariable String id) { return service.cancel(id); }
    @PostMapping("/batches/{id}/items/{itemId}/retry") public Batch retry(@PathVariable String id, @PathVariable String itemId) { return service.retry(id, itemId); }
    @PostMapping("/batches/{id}/items/{itemId}/apply") public Applied applyItem(@PathVariable String id, @PathVariable String itemId, @RequestBody ApplyInput input) { return service.applyItem(id, itemId, input); }
    @PostMapping("/batches/{id}/items/{itemId}/review/apply") public SingleReviewDtos.Result reviewApplyItem(@PathVariable String id, @PathVariable String itemId, @RequestBody SingleReviewDtos.Apply input) {
        var result = review.applyBatch(id, itemId, input);
        return result;
    }
    @GetMapping("/candidates/{id}/cover") public ResponseEntity<byte[]> candidateCover(@PathVariable String id) { return image(covers.candidate(id)); }
    @GetMapping("/songs/{songId}/cover") public ResponseEntity<byte[]> songCover(@PathVariable String songId) { return image(covers.applied(songId)); }
    private ResponseEntity<byte[]> image(MetadataCoverService.Cover cover) {
        return ResponseEntity.ok().contentType(cover.type()).header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(cover.bytes());
    }
}
