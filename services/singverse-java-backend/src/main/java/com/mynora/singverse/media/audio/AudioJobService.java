package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.compute.ComputeNodeDtos.Connection;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.MediaPlaybackService;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AudioJobService {
    private final AudioRoleMapper mapper;
    private final AudioRoleService roles;
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final MediaPlaybackService playback;
    private final TranscodeClient compute;
    private final ObjectMapper json;
    private final Path root;
    private String scanCursor="";
    public AudioJobService(AudioRoleMapper mapper,AudioRoleService roles,MediaSongRepository songs,
            MediaSourceRepository sources,MediaPlaybackService playback,TranscodeClient compute,ObjectMapper json,
            @Value("${singverse.audio.directory:${AUDIO_DERIVED_PATH:./data/audio-roles}}") String directory) {
        this.mapper=mapper;this.roles=roles;this.songs=songs;this.sources=sources;this.playback=playback;this.compute=compute;this.json=json;
        root=Path.of(directory).toAbsolutePath().normalize();
    }
    static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    static ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
    String fingerprint(String song){return MediaSongRepository.fingerprint(songs.get(song));}
    public ObjectNode status(String song){
        var result=roles.role(song);
        var list=result.putArray("jobs");
        for(var row:mapper.forSong(song,fingerprint(song))){
            var view=list.addObject().put("id",text(row,"id")).put("kind",text(row,"kind")).put("state",text(row,"state")).put("error",text(row,"error"));
            try{var data=json.readTree(text(row,"result_json"));view.put("backend",data.path("backend").asText()).put("modelVersion",data.path("modelVersion").asText()).put("fallbackReason",data.path("fallbackReason").asText());if(Set.of("FAST","ANALYZE").contains(text(row,"kind"))){view.put("resultState",data.path("state").asText()).put("reason",data.path("reason").asText());if(data.path("evidence").isObject())view.set("evidence",data.get("evidence"));}if(!result.has("streams") && data.path("streams").isArray())result.set("streams",data.get("streams"));}catch(IOException ignored){}
        }
        if(result.path("streams").size()==1){
            result.put("originalId",result.path("streams").get(0).path("id").asText()).put("state","CONFIRMED").put("origin","SOURCE");
            for(var row:mapper.forSong(song,fingerprint(song)))if(text(row,"kind").equals("SEPARATE")&&text(row,"state").equals("SUCCEEDED")&&Files.isRegularFile(root.resolve(text(row,"id")+".m4a"))){
                var resource=result.putObject("backingResource").put("jobId",text(row,"id")).put("mediaType","audio/mp4");
                try{resource.set("duration",json.readTree(text(row,"result_json")).path("duration"));}catch(IOException ignored){}
                break;
            }
        }
        return result;
    }
    public synchronized ObjectNode submit(String song,String kind){
        return submit(song,kind,"");
    }
    public synchronized ObjectNode submit(String song,String kind,String backend){
        if(!Set.of("","cpu","cuda","rocm","mps","openvino").contains(backend))throw fail(400,"未知推理后端");
        if(!Set.of("PROBE","FAST","ANALYZE","SEPARATE").contains(kind))throw fail(400,"未知音轨任务");
        String fingerprint=fingerprint(song);
        var history=mapper.forSong(song,fingerprint).stream().filter(row->text(row,"kind").equals(kind)).toList();
        for(var row:history)if(Set.of("QUEUED","RUNNING").contains(text(row,"state")))return jobView(row);
        if(Set.of("FAST","ANALYZE").contains(kind)){
            // A completed UNKNOWN analysis must not permanently prevent explicit re-analysis.
            if(!history.isEmpty()&&text(history.get(0),"state").equals("SUCCEEDED"))try{
                if(json.readTree(text(history.get(0),"result_json")).path("state").asText().equals("CONFIDENT"))return jobView(history.get(0));
            }catch(IOException ignored){}
        }else for(var row:history)if(text(row,"state").equals("SUCCEEDED")&&(!kind.equals("SEPARATE")||Files.isRegularFile(root.resolve(text(row,"id")+".m4a"))))return jobView(row);
        var connection=compute.connection();String id=UUID.randomUUID().toString();
        var row=new HashMap<String,Object>();row.put("id",id);row.put("song_id",song);row.put("kind",kind);row.put("state","QUEUED");
        row.put("fingerprint",fingerprint);row.put("backend",backend);row.put("node_origin",connection.origin().toString());row.put("created_at",System.currentTimeMillis());row.put("updated_at",System.currentTimeMillis());
        mapper.insertJob(row);return jobView(row);
    }
    private ObjectNode jobView(Map<String,Object> row){
        return json.createObjectNode().put("id",text(row,"id")).put("kind",text(row,"kind")).put("state",text(row,"state"));
    }
    public synchronized void observe(String song,JsonNode media){
        if(!media.path("streams").isArray() || !media.path("duration").isNumber())return;
        String fp=fingerprint(song);
        if(mapper.forSong(song,fp).stream().anyMatch(row->text(row,"kind").equals("PROBE")&&text(row,"state").equals("SUCCEEDED")))return;
        var row=new HashMap<String,Object>();String id=UUID.randomUUID().toString();
        row.put("id",id);row.put("song_id",song);row.put("kind","PROBE");row.put("state","RUNNING");row.put("fingerprint",fp);row.put("node_origin","");
        row.put("created_at",System.currentTimeMillis());row.put("updated_at",System.currentTimeMillis());mapper.insertJob(row);
        var probe=json.createObjectNode();probe.put("duration",media.path("duration").asDouble());var streams=probe.putArray("streams");
        for(var stream:media.path("streams"))if(stream.path("kind").asText().equals("audio"))streams.add(stream);
        update(id,"SUCCEEDED",probe.toString(),"");roles.verifyImported(song,fp,probe);
    }
    private ObjectNode input(Map<String,Object> row,Connection connection){
        var song=songs.get(text(row,"song_id"));var source=sources.get(song.sourceId());
        var body=json.createObjectNode().put("id",text(row,"id")).put("mode",text(row,"kind"));
        if(!text(row,"backend").isEmpty())body.put("backend",text(row,"backend"));
        if(source.type().equals("WEBDAV"))body.put("reference",text(row,"id"));
        else try{
            Path base=Path.of(source.location()).toRealPath(), file=base.resolve(song.relativePath()).toRealPath();
            if(!file.startsWith(base)||!Files.isRegularFile(file))throw fail(403,"媒体不在来源目录内");
            body.put("path",connection.mediaPath(file));
        }catch(IOException e){throw fail(404,"媒体文件不可读取");}
        return body;
    }
    @Scheduled(fixedDelay=3000,initialDelay=6000,scheduler="audioScheduler")
    public synchronized void tick(){
        for(var row:mapper.pending()){
            String id=text(row,"id");
            try{
                String current;
                try{current=fingerprint(text(row,"song_id"));}catch(ResponseStatusException missing){
                    if(missing.getStatusCode().value()!=404)throw missing;
                    invalidate(row,"源媒体已移除");continue;
                }
                if(!current.equals(text(row,"fingerprint"))){invalidate(row,"源媒体已变化");continue;}
                var connection=compute.connection(text(row,"node_origin"));
                if(text(row,"state").equals("QUEUED")){
                    var body=input(row,connection);
                    update(id,"RUNNING","{}","");
                    try{compute.call(connection,"/internal/v1/audio-roles/tasks","POST",body);}
                    catch(Exception e){update(id,"QUEUED","{}","等待计算节点");throw e;}
                    break;
                }
                var state=compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null);
                String phase=state.path("state").asText();
                if(phase.equals("SUCCEEDED")){
                    var result=state.path("result");
                    if(Set.of("FAST","ANALYZE").contains(text(row,"kind")))roles.applySuggestion(text(row,"song_id"),text(row,"fingerprint"),result);
                    else if(text(row,"kind").equals("SEPARATE"))receive(connection,id,result);
                    roles.verifyImported(text(row,"song_id"),text(row,"fingerprint"),result);
                    update(id,"SUCCEEDED",result.toString(),"");
                    if(text(row,"kind").equals("SEPARATE"))try{compute.call(connection,"/internal/v1/audio-roles/tasks/"+id+"/release","POST",null);}catch(Exception ignored){}

                }else if(Set.of("FAILED","CANCELLED").contains(phase))update(id,state.path("error").asText().equals("REALTIME_PREEMPTED")?"FAILED":phase,state.path("result").isObject()?state.path("result").toString():"{}",state.path("error").asText("计算失败"));
                else if(phase.equals("RUNNING"))update(id,"RUNNING",state.path("result").isObject()?state.path("result").toString():"{}","");
            }catch(ResponseStatusException e){
                if(e.getStatusCode().value()==404)update(id,text(row,"state").equals("RUNNING")?"QUEUED":"FAILED","{}",text(row,"state").equals("RUNNING")?"节点重启后重新排队":"媒体文件不可读取");
                else if(e.getStatusCode().value()==409)update(id,"FAILED","{}","节点配置或任务状态已变化，请重试");
                else if(Set.of(400,403,413,422).contains(e.getStatusCode().value()))update(id,"FAILED","{}","素材或模型不支持此任务");
                else update(id,text(row,"state"),text(row,"result_json"),"等待计算节点或可用空间");
                if(Set.of(429,502,503).contains(e.getStatusCode().value()))break;
            }catch(Exception e){update(id,text(row,"state"),text(row,"result_json"),"等待计算节点");}
        }
    }
    @Scheduled(fixedDelay=10000,initialDelay=15000,scheduler="audioScheduler")
    public synchronized void discover(){
        try {
            var capability=compute.call(compute.connection(),"/internal/v1/status","GET",null);
            if(!capability.path("capabilities").toString().contains("media-probe"))return;
            boolean fast=capability.path("capabilities").toString().contains("audio-role-fast");
            boolean classifier=capability.path("capabilities").toString().contains("audio-role-fast-classifier");
            boolean paired=capability.path("capabilities").toString().contains("audio-role-fast-v2");
            var candidates=mapper.candidates(scanCursor);
            if(candidates.isEmpty()){scanCursor="";return;}
            for(String song:candidates){
                scanCursor=song;var history=mapper.forSong(song,fingerprint(song));
                if(history.isEmpty()){submit(song,"PROBE");continue;}
                if(!fast)continue;
                var confirmed=roles.role(song);if(confirmed!=null&&confirmed.path("state").asText().equals("CONFIRMED"))continue;
                var previous=history.stream().filter(row->text(row,"kind").equals("FAST")).findFirst();
                // Retry once when an explicitly installed classifier becomes available; never loop on ambiguity.
                boolean missing=previous.isPresent()&&text(previous.get(),"state").equals("SUCCEEDED")&&json.readTree(text(previous.get(),"result_json")).path("reason").asText().equals("FAST_MODEL_MISSING");
                boolean obsolete=false;
                if(paired&&previous.isPresent()&&text(previous.get(),"state").equals("SUCCEEDED")){
                    var prior=json.readTree(text(previous.get(),"result_json"));
                    obsolete=prior.path("state").asText().equals("UNKNOWN")&&!prior.path("evidence").path("analysisVersion").asText().equals("fast-vocal-v2");
                }
                if(previous.isPresent()&&!(missing&&classifier)&&!obsolete)continue;
                for(var row:history)if(text(row,"kind").equals("PROBE")&&text(row,"state").equals("SUCCEEDED")){
                    var probe=json.readTree(text(row,"result_json"));if(probe.path("streams").size()==2)submit(song,"FAST");break;
                }
            }
        } catch(Exception unavailable){/* Optional compute must not block scans. */}
    }
    private void invalidate(Map<String,Object> row,String reason){
        if(text(row,"state").equals("RUNNING"))try{compute.call(compute.connection(text(row,"node_origin")),"/internal/v1/audio-roles/tasks/"+text(row,"id")+"/cancel","POST",null);}catch(Exception ignored){}
        update(text(row,"id"),"FAILED","{}",reason);
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000,scheduler="audioScheduler")
    public void collect(){
        if(!Files.isDirectory(root))return;
        try(var paths=Files.list(root)){
            for(Path path:paths.toList()){
                String name=path.getFileName().toString();
                if(!name.matches("[a-f0-9-]{36}\\.(m4a|part)")||Files.getLastModifiedTime(path).toMillis()>System.currentTimeMillis()-86400000)continue;
                boolean remove=name.endsWith(".part");var job=mapper.job(name.substring(0,36));
                if(job==null)remove=true;
                else try{remove|=!text(job,"fingerprint").equals(fingerprint(text(job,"song_id")))||!text(job,"state").equals("SUCCEEDED");}
                catch(ResponseStatusException missing){if(missing.getStatusCode().value()==404)remove=true;else throw missing;}
                if(remove)Files.deleteIfExists(path);
            }
        }catch(IOException ignored){/* Try again without changing business state. */}
    }
    private void update(String id,String state,String result,String error){mapper.updateJob(id,state,result,error,System.currentTimeMillis());}
    private void receive(Connection connection,String id,JsonNode result)throws IOException{
        if(!result.path("artifact").asText().equals(id+".m4a"))throw fail(422,"伴奏产物无效");
        Files.createDirectories(root);
        if(Files.getFileStore(root).getUsableSpace()<2L*1024*1024*1024)throw fail(507,"伴奏存储空间不足");
        Path temporary=root.resolve(id+".part");
        var response=compute.artifact(connection,"/internal/v1/audio-roles/tasks/"+id+"/artifact",null);
        try(var in=response.body();var out=Files.newOutputStream(temporary)){
            if(response.statusCode()!=200)throw fail(response.statusCode()==404?422:502,"伴奏读取失败");
            java.security.MessageDigest digest;
            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            byte[] buffer=new byte[65536];long size=0;int length;
            while((length=in.read(buffer))!=-1){size+=length;if(size>1024L*1024*1024)throw fail(413,"伴奏超过大小预算");digest.update(buffer,0,length);out.write(buffer,0,length);}
            if(size<1024||size!=result.path("size").asLong(-1)||!java.util.HexFormat.of().formatHex(digest.digest()).equals(result.path("sha256").asText()))throw fail(422,"伴奏校验失败");
        }catch(Exception e){Files.deleteIfExists(temporary);throw e;}
        Files.move(temporary,root.resolve(id+".m4a"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public synchronized void cancel(String song,String id){
        var row=require(song,id);if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(text(row,"state")))return;
        update(id,"CANCELLED","{}","");
        if(text(row,"state").equals("RUNNING"))try{compute.call(compute.connection(text(row,"node_origin")),"/internal/v1/audio-roles/tasks/"+id+"/cancel","POST",null);}catch(Exception ignored){}
    }
    Map<String,Object> require(String song,String id){
        var row=mapper.job(id);if(row==null||!text(row,"song_id").equals(song))throw fail(404,"任务不存在");return row;
    }
    public void source(String id,String token,String range,boolean head,HttpServletResponse response)throws IOException{
        var row=mapper.job(id);if(row==null||!text(row,"state").equals("RUNNING"))throw fail(404,"媒体授权不存在");
        var connection=compute.connection(text(row,"node_origin"));
        if(token==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),("Bearer "+connection.token()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw fail(401,"内部认证失败");
        if(!fingerprint(text(row,"song_id")).equals(text(row,"fingerprint")))throw fail(409,"源媒体已变化");
        playback.play(text(row,"song_id"),range,head,response);
    }
    public void accompaniment(String song,String id,String range,boolean head,HttpServletResponse response)throws IOException{
        var row=require(song,id);
        if(!text(row,"state").equals("SUCCEEDED")||!text(row,"kind").equals("SEPARATE")||!text(row,"fingerprint").equals(fingerprint(song)))throw fail(404,"伴奏版本已失效");
        playback.playDerived(root,id+".m4a",range,head,response);
    }
}
