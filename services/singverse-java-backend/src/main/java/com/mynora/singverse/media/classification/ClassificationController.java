package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/v1/classification")
public class ClassificationController {
    @org.springframework.beans.factory.annotation.Autowired private ClassificationCoverService covers;
    private final ClassificationService service;
    private final ClassificationSettings settings;
    private final ClassificationModel model;
    public ClassificationController(ClassificationService service,ClassificationSettings settings,ClassificationModel model){this.service=service;this.settings=settings;this.model=model;}
    @GetMapping("/settings") public Settings settings(){return settings.read();}
    @PutMapping("/settings") public Settings save(@RequestBody SaveSettings input){return settings.save(input);}
    @PostMapping("/settings/test") public Map<String,Object> test(){
        try {model.generate(settings.require(),new Input("connection-test","",List.of(),null,null,"UNKNOWN","UNKNOWN",List.of()),service.tags(),Set.of("GENRE"));return Map.of("success",true,"message","模型连接和结构化输出验证通过；不代表分类准确率");}
        catch(ClassificationModel.ModelFailure e){throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_GATEWAY,e.getMessage());}
    }
    @GetMapping("/tags") public List<Tag> tags(){return service.tags();}
    @PostMapping("/tags") @ResponseStatus(HttpStatus.CREATED) public Tag create(@RequestBody SaveTag input){return service.saveTag(null,input);}
    @PutMapping("/tags/{id}") public Tag update(@PathVariable String id,@RequestBody SaveTag input){return service.saveTag(id,input);}
    @GetMapping("/tags/{id}/cover") public org.springframework.http.ResponseEntity<byte[]> cover(@PathVariable String id){return covers.image(id,false);}
    @PutMapping(value="/tags/{id}/cover",consumes="multipart/form-data") public Tag uploadCover(@PathVariable String id,@RequestParam(defaultValue="") String version,@RequestParam org.springframework.web.multipart.MultipartFile file){return covers.upload(id,version,file);}
    @DeleteMapping("/tags/{id}/cover") public Tag removeCover(@PathVariable String id,@RequestParam String version){return covers.remove(id,version);}
    @GetMapping("/songs/{id}") public State state(@PathVariable String id){return service.state(id);}
    @PutMapping("/songs/{id}") public State change(@PathVariable String id,@RequestBody Change input){return service.change(id,input);}
    @GetMapping("/songs/{id}/history") public List<History> history(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.history(id,page,size);}
    @PostMapping("/history/{id}/undo") public State undo(@PathVariable String id,@RequestBody Undo input){return service.undo(id,input);}
    @PostMapping("/preview") public Preview preview(@RequestBody Start input){return service.preview(input);}
    @PostMapping("/tasks") @ResponseStatus(HttpStatus.CREATED) public Task start(@RequestBody Start input){return service.start(input);}
    @GetMapping("/tasks") public List<Task> tasks(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.tasks(page,size);}
    @GetMapping("/tasks/{id}") public Task task(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String status){return service.task(id,page,size,status);}
    @PostMapping("/tasks/{id}/pause") public Task pause(@PathVariable String id){return service.control(id,"pause");}
@PostMapping("/tasks/{id}/resume") public Task resume(@PathVariable String id){return service.control(id,"resume");}
@PostMapping("/tasks/{id}/cancel") public Task cancel(@PathVariable String id){return service.control(id,"cancel");}
    @PostMapping("/tasks/{taskId}/items/{id}/retry") public Task retry(@PathVariable String taskId,@PathVariable String id){return service.retry(taskId,id);}
    @PostMapping("/tasks/{taskId}/items/{id}/apply") public State review(@PathVariable String taskId,@PathVariable String id,@RequestBody Review input){return service.review(taskId,id,input);}
}
