package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audio-processing")
public class AudioProcessingController {
    private final AudioProcessingService service;
    private final TranscodeClient compute;
    public AudioProcessingController(AudioProcessingService service,TranscodeClient compute){this.service=service;this.compute=compute;}
    @GetMapping("/songs")
    public ObjectNode songs(@RequestParam(defaultValue="")String title,@RequestParam(defaultValue="")String artist,@RequestParam(required=false)String sourceId,@RequestParam(required=false)Integer streams,@RequestParam(defaultValue="")String state,@RequestParam(required=false)String songId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size){
        return service.list(new AudioProcessingService.Filter(title,artist,sourceId,streams,state,songId),page,size);
    }
    @PostMapping("/batches/preview")
    public ObjectNode preview(@RequestBody AudioProcessingService.Selection body){return service.preview(body);}
    @GetMapping("/batches")
    public ObjectNode batches(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return service.batches(page,size);}
    @GetMapping("/batches/{id}")
    public ObjectNode detail(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="50")int size){return service.detail(id,page,size);}
    @PostMapping("/batches/{id}/submit")
    public ObjectNode submit(@PathVariable String id){return service.start(id);}
    @PostMapping("/batches/{id}/cancel")
    public ObjectNode cancel(@PathVariable String id){return service.cancel(id);}
    @PostMapping("/batches/{id}/items/{song}/cancel")
    public void cancelItem(@PathVariable String id,@PathVariable String song){service.cancelItem(id,song);}
    @PostMapping("/batches/{id}/retry")
    public ObjectNode retry(@PathVariable String id,@RequestBody(required=false)JsonNode body){return service.retry(id,body==null?"":body.path("backend").asText(""));}
    @GetMapping("/node")
    public JsonNode node(@RequestParam(defaultValue="false")boolean refresh){return compute.call(compute.connection(),"/internal/v1/audio-roles/models"+(refresh?"?refresh=true":""),"GET",null);}
    @PostMapping("/model/download")
    public JsonNode download(@RequestParam(defaultValue="htdemucs")String model){AudioModelManifest.maxBytes(model);return compute.call(compute.connection(),"/internal/v1/audio-roles/models/download?model="+model,"POST",null);}
    @PostMapping("/model/cancel")
    public JsonNode cancelModel(){return model("cancel",null);}
    @PostMapping("/model/test")
    public JsonNode test(@RequestBody JsonNode body){return model("test",body);}
    @PostMapping("/model/select")
    public JsonNode select(@RequestBody JsonNode body){return model("select",body);}
    private JsonNode model(String action,JsonNode body){return compute.call(compute.connection(),"/internal/v1/audio-roles/models/"+action,"POST",body);}
    @PostMapping(value="/model/import",consumes="application/octet-stream")
    public JsonNode upload(HttpServletRequest request,@RequestParam(defaultValue="htdemucs")String model)throws java.io.IOException {
        long maximum=AudioModelManifest.maxBytes(model);long length=request.getContentLengthLong();if(length>maximum)throw AudioJobService.fail(413,"模型文件超过该模型清单的大小限制");
        return compute.uploadAudioModel(request.getInputStream(),length,model);
    }
}
