package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/lx-downloads")
public class LxDownloadController {
    private final LxDownloadService service;
    private final LxCoverService covers;
    public LxDownloadController(LxDownloadService service,LxCoverService covers){this.service=service;this.covers=covers;}
    @GetMapping("/covers/{id}") public ResponseEntity<byte[]> cover(@PathVariable String id){var cover=covers.get(id);return ResponseEntity.ok().contentType(cover.type()).cacheControl(CacheControl.noStore()).body(cover.bytes());}
    @GetMapping("/status") public Map<String,Object> status(){return service.status();}
    @GetMapping("/sources") public List<Map<String,Object>> sources(){return service.scripts();}
    @PostMapping("/sources") public Map<String,Object> save(@RequestBody SourceInput input){return service.importScript(input);}
    @PostMapping("/sources/{id}/activate") public Map<String,Object> activate(@PathVariable String id){return service.activate(id);}
    @PostMapping("/sources/{id}/test") public Map<String,Object> test(@PathVariable String id){return service.test(id);}
    @DeleteMapping("/sources/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){service.deleteScript(id);}
    public record Search(String query,String platform,int page) {}
    @PostMapping("/catalog/search") public Map<String,Object> search(@RequestBody Search input){return service.search(input.query(),input.platform(),input.page());}
    @PostMapping("/catalog/preview") public Map<String,Object> preview(@RequestBody PreviewInput input){return service.preview(input);}
    @GetMapping("/catalog/tracks/{id}") public TrackView detail(@PathVariable String id){return service.detail(id);}
    public record Selection(List<String> trackIds) {}
    @PostMapping("/catalog/states") public List<TrackView> states(@RequestBody Selection input){return service.trackStates(input.trackIds());}
    @GetMapping("/batches") public Map<String,Object> batches(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.batches(page,size);}
    @PostMapping("/batches") public Map<String,Object> create(@RequestBody BatchInput input){return service.create(input);}
    @GetMapping("/batches/{id}") public Map<String,Object> batch(@PathVariable String id){return service.batch(id);}
    @GetMapping("/batches/{id}/items") public Map<String,Object> items(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.items(id,page,size);}
    @PostMapping("/batches/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(@PathVariable String id){service.cancel(id);}
    @PostMapping("/batches/{id}/retry") public Map<String,Object> retry(@PathVariable String id){return service.retry(id);}
    @ExceptionHandler({LxNetwork.LxNetworkFailure.class,LxRuntime.LxFailure.class})
    public ResponseEntity<ProblemDetail> upstream(RuntimeException error){var problem=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,clean(error.getMessage()));return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);}
}
