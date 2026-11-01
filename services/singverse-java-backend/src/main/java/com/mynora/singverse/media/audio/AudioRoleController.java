package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.tv.TvService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class AudioRoleController {
    private final AudioRoleService roles;
    private final TvService tv;
    @org.springframework.beans.factory.annotation.Autowired private AudioJobService jobs;

    public AudioRoleController(AudioRoleService roles, TvService tv) {
        this.roles=roles; this.tv=tv;
    }

    @GetMapping("/api/v1/media-songs/{song}/audio-roles")
    public ObjectNode adminRole(@PathVariable String song) { return jobs.status(song); }

    @PutMapping("/api/v1/media-songs/{song}/audio-roles")
    public ObjectNode adminCorrect(@PathVariable String song,@RequestBody JsonNode body) {
        return correct(song,body);
    }

    @GetMapping("/api/v1/party/audio-roles/songs/{song}")
    public ObjectNode partyRole(@PathVariable String song,HttpServletRequest request) {
        authenticate(request); return jobs.status(song);
    }

    @PutMapping("/api/v1/party/audio-roles/songs/{song}")
    public ObjectNode partyCorrect(@PathVariable String song,@RequestBody JsonNode body,HttpServletRequest request) {
        authenticate(request); return correct(song,body);
    }

    @GetMapping("/api/v1/tv/audio-roles/songs/{song}")
    public ObjectNode tvRole(@PathVariable String song,HttpServletRequest request) {
        authenticate(request); return jobs.status(song);
    }

    @PutMapping("/api/v1/tv/audio-roles/songs/{song}")
    public ObjectNode tvCorrect(@PathVariable String song,@RequestBody JsonNode body,HttpServletRequest request) {
        authenticate(request); return correct(song,body);
    }

    @PostMapping("/api/v1/media-songs/{song}/audio-jobs")
    public ObjectNode submit(@PathVariable String song,@RequestBody JsonNode body){ return jobs.submit(song,body.path("kind").asText()); }
    @PostMapping("/api/v1/media-songs/{song}/audio-jobs/{id}/cancel")
    public void cancel(@PathVariable String song,@PathVariable String id){ jobs.cancel(song,id); }
    @RequestMapping(value="/api/v1/media-songs/{song}/accompaniment/{id}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void artifact(@PathVariable String song,@PathVariable String id,@RequestHeader(value="Range",required=false)String range,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        jobs.accompaniment(song,id,range,request.getMethod().equals("HEAD"),response);
    }
    @RequestMapping(value="/api/v1/tv/audio-roles/songs/{song}/accompaniment/{id}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void tvArtifact(@PathVariable String song,@PathVariable String id,@RequestHeader(value="Range",required=false)String range,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        authenticate(request);jobs.accompaniment(song,id,range,request.getMethod().equals("HEAD"),response);
    }
    @RequestMapping(value="/api/v1/internal/audio-input/{id}",method={RequestMethod.GET,RequestMethod.HEAD})
    public void input(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String token,@RequestHeader(value="Range",required=false)String range,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException{
        jobs.source(id,token,range,request.getMethod().equals("HEAD"),response);
    }

    private void authenticate(HttpServletRequest request) {
        String bearer=request.getHeader("Authorization");
        if(bearer==null || !bearer.startsWith("Bearer ")) throw fail(401,"请先连接");
        tv.authenticate(bearer.substring(7));
    }

    private static org.springframework.web.server.ResponseStatusException fail(int status,String message) { return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.valueOf(status),message); }

    private ObjectNode correct(String song,JsonNode body) {
        if(!body.path("revision").isIntegralNumber() || !body.path("revision").canConvertToLong() || body.path("revision").asLong()<0)
            throw fail(400,"缺少音轨修订号");
        return roles.correct(song,body.path("originalId").asText(null),
                body.path("backingId").asText(null),body.path("revision").asLong());
    }
}
