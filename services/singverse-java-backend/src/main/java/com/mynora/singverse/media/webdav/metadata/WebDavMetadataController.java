package com.mynora.singverse.media.webdav.metadata;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletResponse;
@RestController
public class WebDavMetadataController {
    private final WebDavMetadataService service;
    private final WebDavMetadataInput input;
    private final WebDavShareJobs jobs;
    private final com.mynora.singverse.media.metadata.MetadataFileService files;
    private final com.mynora.singverse.media.repository.MediaSourceRepository sources;
    public WebDavMetadataController(WebDavMetadataService service,WebDavMetadataInput input,WebDavShareJobs jobs,com.mynora.singverse.media.metadata.MetadataFileService files,com.mynora.singverse.media.repository.MediaSourceRepository sources){this.files=files;this.sources=sources;this.jobs=jobs;this.service=service;this.input=input;}
    @PostMapping(value="/api/v1/media-sources/{id}/embedded-metadata/share",consumes="application/zip")public Map<String,Integer> importBundle(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request)throws java.io.IOException{if(request.getContentLengthLong()>WebDavShareJobs.UPLOAD_LIMIT)throw WebDavAccess.failure(413,"分享包超过 8 GiB");return jobs.importStream(id,request.getInputStream());}
    public record ExportRequest(String baseId){}
    @PostMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs")@ResponseStatus(HttpStatus.ACCEPTED)public Map<String,Object> createJob(@PathVariable String id,@RequestBody(required=false)ExportRequest value){return jobs.create(id,value==null?null:value.baseId());}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/latest")public Map<String,Object> latestJob(@PathVariable String id){return jobs.latest(id);}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}")public Map<String,Object> getJob(@PathVariable String id,@PathVariable String jobId){return jobs.get(id,jobId);}
    @PostMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}/resume")public void resumeJob(@PathVariable String id,@PathVariable String jobId){jobs.resume(id,jobId);}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}/index")public ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> index(@PathVariable String id,@PathVariable String jobId){return ResponseEntity.ok().header("Content-Disposition","attachment; filename=index.json").cacheControl(CacheControl.noStore()).body(jobs.index(id,jobId));}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}/parts/{part}")public org.springframework.core.io.Resource part(@PathVariable String id,@PathVariable String jobId,@PathVariable int part){return new org.springframework.core.io.FileSystemResource(jobs.part(id,jobId,part));}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}/deletions/{part}")public org.springframework.core.io.Resource deletions(@PathVariable String id,@PathVariable String jobId,@PathVariable int part){return new org.springframework.core.io.FileSystemResource(jobs.deletions(id,jobId,part));}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata/share-jobs/{jobId}/download")public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> download(@PathVariable String id,@PathVariable String jobId){var job=jobs.get(id,jobId);if(!"SUCCEEDED".equals(job.get("state")))throw WebDavAccess.failure(409,"Share package is still being generated");return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore()).header("Content-Disposition","attachment; filename=singverse-share-set.zip").body(output->jobs.download(id,jobId,output));}
    public record Settings(boolean enabled,boolean paused,String accessGroup,List<String> privateOrigins){}
    public record Refresh(List<String> songIds,boolean deep){}
    @GetMapping("/api/v1/media-sources/{id}/embedded-metadata")public Map<String,Object> summary(@PathVariable String id){var result=sources.get(id).type().equals("LOCAL")?files.localSummary(id):service.summary(id);result.put("files",files.status(id));return result;}
    @PutMapping("/api/v1/media-sources/{id}/embedded-metadata")public Map<String,Object> configure(@PathVariable String id,@RequestBody Settings value){return service.configure(id,value.enabled(),value.paused(),value.accessGroup(),value.privateOrigins());}
    @PostMapping("/api/v1/media-sources/{id}/embedded-metadata/confirm-empty")public ResponseEntity<Void> empty(@PathVariable String id){service.confirmEmpty(id);return ResponseEntity.noContent().build();}
    @GetMapping("/api/v1/media-songs/{id}/embedded-metadata")public Map<String,Object> detail(@PathVariable String id){return service.detail(id);}
    @GetMapping("/api/v1/media-songs/{id}/embedded-metadata/cover")public ResponseEntity<byte[]> cover(@PathVariable String id){return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(service.cover(id));}
    @PostMapping("/api/v1/media-songs/embedded-metadata/refresh")@ResponseStatus(HttpStatus.ACCEPTED)public Map<String,Object> refresh(@RequestBody Refresh value){return service.refresh(value.songIds(),value.deep());}
    @GetMapping("/api/v1/internal/metadata-input/{id}")public void read(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="Range",required=false)String range,HttpServletResponse response)throws java.io.IOException{input.read(id,authorization,range,response);}
}
