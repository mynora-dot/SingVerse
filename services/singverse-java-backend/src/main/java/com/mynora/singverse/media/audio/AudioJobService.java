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
    private final MediaPlaybackService playback;
    private final TranscodeClient compute;
    private final ObjectMapper json;
    private final Path root;
    private String scanCursor="";
    private String discoveryRound=UUID.randomUUID().toString();
    private String scopeBatchCursor="",scopeSongCursor="";
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mynora.singverse.background.BackgroundTaskService backgroundTasks;
    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String, InputStream> receiving = new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired private AudioDispatch dispatch;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationEventPublisher events;
    @jakarta.annotation.PostConstruct void initializeDispatch(){dispatch.registerJobs(this::tick);}
    void wakeup(){if(dispatch!=null)dispatch.jobs(0);}
    public AudioJobService(AudioRoleMapper mapper,AudioRoleService roles,MediaSongRepository songs,
            MediaPlaybackService playback,TranscodeClient compute,ObjectMapper json,
            @Value("${singverse.audio.directory:${AUDIO_DERIVED_PATH:./data/audio-roles}}") String directory) {
        this.mapper=mapper;this.roles=roles;this.songs=songs;this.playback=playback;this.compute=compute;this.json=json;
        root=Path.of(directory).toAbsolutePath().normalize();
    }
    static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    AudioRecipe recipe(Map<String,Object> row){return AudioRecipe.stored(json,row.get("recipe_json"));}
    Path artifactPath(Map<String,Object> row){return root.resolve(text(row,"id")+"."+recipe(row).extension());}
    static ResponseStatusException fail(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
    String fingerprint(String song){return MediaSongRepository.fingerprint(songs.get(song));}
    public ObjectNode status(String song) {
        var stored = songs.get(song);
        return status(stored, mapper.role(song), mapper.forSong(song, MediaSongRepository.fingerprint(stored)));
    }
    ObjectNode status(com.mynora.singverse.media.dto.MediaSongDtos.Song song,
            Map<String,Object> role, List<Map<String,Object>> history) {
        return status(song,role,history,mapper.selection(song.id()));
    }
    ObjectNode status(com.mynora.singverse.media.dto.MediaSongDtos.Song song,
            Map<String,Object> role, List<Map<String,Object>> history, Map<String,Object> selection) {
        var result=roles.view(song, role);
        var list=result.putArray("jobs");
        String selected=selection!=null&&text(selection,"fingerprint").equals(MediaSongRepository.fingerprint(song))?text(selection,"job_id"):"";
        if(!selected.isEmpty()&&history.stream().noneMatch(row->text(row,"id").equals(selected))){
            var active=mapper.job(selected);
            if(active!=null&&text(active,"song_id").equals(song.id())&&text(active,"fingerprint").equals(MediaSongRepository.fingerprint(song))){history=new ArrayList<>(history);history.add(active);}
        }
        var versions=result.putArray("versions");
        ObjectNode activeResource=null;
        for(var row:history){
            var view=list.addObject().put("id",text(row,"id")).put("kind",text(row,"kind")).put("state",text(row,"state")).put("error",text(row,"error"));
            var specification=recipe(row);
            if(text(row,"kind").equals("SEPARATE"))view.setAll(specification.view(json));
            try{var data=json.readTree(text(row,"result_json"));view.put("backend",data.path("backend").asText()).put("modelVersion",data.path("modelVersion").asText()).put("fallbackReason",data.path("fallbackReason").asText());
                for(String field:List.of("stage","timingsMs","progress","inference","sourceReused","sourceOffsetSeconds","precision"))if(data.has(field))view.set(field,data.get(field));
                if(Set.of("FAST","ANALYZE").contains(text(row,"kind"))){view.put("resultState",data.path("state").asText()).put("reason",data.path("reason").asText());if(data.path("evidence").isObject())view.set("evidence",data.get("evidence"));}if(!result.has("streams") && data.path("streams").isArray())result.set("streams",data.get("streams"));}catch(IOException ignored){}
            if(text(row,"kind").equals("SEPARATE")&&text(row,"state").equals("SUCCEEDED")&&Files.isRegularFile(artifactPath(row))){
                var resource=specification.view(json).put("jobId",text(row,"id")).put("mediaType",specification.outputFormat().equals("flac")?"audio/flac":"audio/mp4");
                try{resource.set("duration",json.readTree(text(row,"result_json")).path("duration"));}catch(IOException ignored){}
                versions.add(resource);
                if(specification.playable()&&(activeResource==null||text(row,"id").equals(selected)))activeResource=resource;
            }
        }
        if(result.path("streams").size()==1){
            if(!result.path("state").asText().equals("CONFIRMED"))result.put("originalId",result.path("streams").get(0).path("id").asText()).put("state","CONFIRMED").put("origin","SOURCE");
        }
        if(activeResource!=null){result.set("backingResource",activeResource);activeResource.put("active",true);}
        return result;
    }
    public synchronized ObjectNode submit(String song,String kind){
        return submit(song,kind,"");
    }
    public synchronized ObjectNode submit(String song,String kind,String backend){
        return submit(song,kind,backend,List.of());
    }
    public synchronized ObjectNode submit(String song,String kind,String backend,List<String> origins){
        return submit(song,kind,backend,origins,AudioRecipe.legacy());
    }
    public synchronized ObjectNode submit(String song,String kind,String backend,List<String> origins,AudioRecipe specification){
        String chosen=backend==null||backend.equals("auto")?"":backend;
        return backgroundTasks==null ? submitRecorded(song,kind,chosen,origins,specification)
                : backgroundTasks.audioTransaction(()->submitRecorded(song,kind,chosen,origins,specification));
    }
    private ObjectNode submitRecorded(String song,String kind,String backend,List<String> origins,AudioRecipe specification){
        specification.validateBackend(backend);
        if(!Set.of("PROBE","FAST","ANALYZE","SEPARATE").contains(kind))throw fail(400,"未知音轨任务");
        if(!kind.equals("SEPARATE")&&!specification.legacyDefault())throw fail(400,"伴奏方案仅用于生成伴奏");
        String fingerprint=fingerprint(song);
        if(kind.equals("SEPARATE")){
            var confirmed=roles.role(song);
            String backing=confirmed!=null&&confirmed.path("state").asText().equals("CONFIRMED")?confirmed.path("backingId").asText(""):"";
            if(specification.sourceAudioId()!=null&&!specification.sourceAudioId().equals(backing))validateSource(song,fingerprint,specification.sourceAudioId());
            if(specification.sourceAudioId()==null&&!backing.isEmpty())specification=specification.source(backing);
        }
        AudioRecipe requested=specification;
        var history=mapper.forSong(song,fingerprint).stream().filter(row->text(row,"kind").equals(kind)&&(!kind.equals("SEPARATE")||sameRecipe(row,requested,backend))).toList();
        for(var row:history)if(Set.of("QUEUED","RUNNING").contains(text(row,"state")))return recordSubmission(row,song,fingerprint,origins,false);
        if(Set.of("FAST","ANALYZE").contains(kind)){
            // A completed UNKNOWN analysis must not permanently prevent explicit re-analysis.
            if(!history.isEmpty()&&text(history.get(0),"state").equals("SUCCEEDED"))try{
                if(json.readTree(text(history.get(0),"result_json")).path("state").asText().equals("CONFIDENT"))return recordSubmission(history.get(0),song,fingerprint,origins,false);
            }catch(IOException ignored){}
        }else for(var row:history)if(text(row,"state").equals("SUCCEEDED")&&(!kind.equals("SEPARATE")||Files.isRegularFile(artifactPath(row))))return recordSubmission(row,song,fingerprint,origins,false);
        var connection=compute.connection();String id=UUID.randomUUID().toString();
        var row=new HashMap<String,Object>();row.put("id",id);row.put("song_id",song);row.put("kind",kind);row.put("state","QUEUED");
        row.put("fingerprint",fingerprint);row.put("backend",backend);row.put("node_origin",connection.origin().toString());row.put("created_at",System.currentTimeMillis());row.put("updated_at",System.currentTimeMillis());
        row.put("recipe_json",specification.view(json).toString());
        mapper.insertJob(row);var result=recordSubmission(row,song,fingerprint,origins,true);wakeup();return result;
    }
    private boolean sameRecipe(Map<String,Object> row,AudioRecipe specification,String backend){
        var previous=recipe(row);
        return previous.legacyDefault()&&specification.legacyDefault()||previous.fingerprint(json,text(row,"backend")).equals(specification.fingerprint(json,backend));
    }
    private void validateSource(String song,String fingerprint,String identifier){
        String[] parts=identifier.split(":");String stream=parts[0]+":"+parts[1];
        for(var job:mapper.forSong(song,fingerprint))try{
            for(var item:json.readTree(text(job,"result_json")).path("streams"))
                if(item.path("id").asText().equals(stream)&&(parts.length==2||item.path("channels").asInt()==2))return;
        }catch(IOException ignored){}
        throw fail(422,"请先读取音轨，再试听确认所选伴奏音轨或声道");
    }
    private ObjectNode recordSubmission(Map<String,Object> row,String song,String fp,List<String> origins,boolean created){
        if(backgroundTasks!=null)backgroundTasks.audioSubmitted(text(row,"id"),text(row,"kind"),song,fp,origins,created);
        return jobView(row);
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
    private ObjectNode input(Map<String,Object> row){
        var body=json.createObjectNode().put("id",text(row,"id")).put("mode",text(row,"kind"));
        if(!text(row,"backend").isEmpty())body.put("backend",text(row,"backend"));
        body.put("reference",text(row,"id"));
        var specification=recipe(row);
        if(text(row,"kind").equals("SEPARATE")&&!specification.legacyDefault()){
            var node=compute.call(compute.connection(text(row,"node_origin")),"/internal/v1/audio-roles/models","GET",null);
            if(node.path("separation").path("version").asInt()<1)throw fail(422,"计算节点尚不支持伴奏方案，请升级节点");
            if(!specification.ready(node,text(row,"backend")))throw fail(429,"等待伴奏模型与独立 CPU 运行环境就绪");
            var options=specification.view(json);options.remove(List.of("recipeVersion","precisionPolicy","modelDigest","dspFilterVersion"));body.setAll(options);
        }
        return body;
    }
    @Scheduled(fixedDelay=3000,initialDelay=6000,scheduler="audioScheduler")
    public void scheduledTick(){dispatch.jobs(0);}
    public void tick(){
        if (!ticking.compareAndSet(false, true)) return;
        try {
        var pending=mapper.pending();
        boolean running=pending.stream().anyMatch(row->text(row,"state").equals("RUNNING"));
        for(var row:pending){
            if(running && text(row,"state").equals("QUEUED"))continue;
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
                    var body=input(row);
                    // Keep the request compatible with older compute nodes which reject extra fields.
                    var execution=json.createObjectNode();
                    execution.putObject("timingsMs").put("queue",Math.max(0,System.currentTimeMillis()-((Number)row.getOrDefault("created_at",System.currentTimeMillis())).longValue()));
                    update(id,"RUNNING",execution.toString(),"");
                    try{compute.call(connection,"/internal/v1/audio-roles/tasks","POST",body);}
                    catch(Exception e){update(id,"QUEUED","{}","等待计算节点");throw e;}
                    finally {
                        var latest=mapper.job(id);
                        // Cancellation can reach compute before the in-flight POST has created its task.
                        if(latest!=null&&text(latest,"state").equals("CANCELLED"))
                            try{compute.call(connection,"/internal/v1/audio-roles/tasks/"+id+"/cancel","POST",null);}catch(Exception ignored){}
                    }
                    if(dispatch!=null)dispatch.jobs(1000);
                    break;
                }
                var state=compute.call(connection,"/internal/v1/audio-roles/tasks/"+id,"GET",null);
                var result=executionResult(row,state.path("result"));
                String phase=state.path("state").asText();
                if(phase.equals("SUCCEEDED")){
                    if(Set.of("FAST","ANALYZE").contains(text(row,"kind"))) {
                        synchronized (this) { checkReceiving(id); roles.applySuggestion(text(row,"song_id"),text(row,"fingerprint"),result); }
                    }
                    else if(text(row,"kind").equals("SEPARATE"))receive(connection,id,result);
                    synchronized (this) {
                        checkReceiving(id);
                        boolean matches=false;
                        try{matches=fingerprint(text(row,"song_id")).equals(text(row,"fingerprint"));}
                        finally{if(!matches&&text(row,"kind").equals("SEPARATE"))Files.deleteIfExists(artifactPath(row));}
                        if(!matches)throw fail(409,"源媒体已变化");
                        roles.verifyImported(text(row,"song_id"),text(row,"fingerprint"),result);
                        result.withObject("/timingsMs").put("endToEnd",Math.max(0,System.currentTimeMillis()-((Number)row.getOrDefault("created_at",System.currentTimeMillis())).longValue()));
                        if(text(row,"kind").equals("SEPARATE")&&recipe(row).playable())preserveSelection(row);
                        update(id,"SUCCEEDED",result.toString(),"");
                    }
                    if(text(row,"kind").equals("SEPARATE"))try{compute.call(connection,"/internal/v1/audio-roles/tasks/"+id+"/release","POST",null);}catch(Exception ignored){}

                }else if(Set.of("FAILED","CANCELLED").contains(phase))update(id,state.path("error").asText().equals("REALTIME_PREEMPTED")?"FAILED":phase,result.toString(),state.path("error").asText("计算失败")+(result.path("diagnostic").asText().isBlank()?"":"："+result.path("diagnostic").asText()));
                else if(phase.equals("RUNNING")){
                    update(id,"RUNNING",result.toString(),"");
                    if(dispatch!=null)dispatch.jobs(1000);
                }
            }catch(ResponseStatusException e){
                if(e.getStatusCode().value()==404)update(id,text(row,"state").equals("RUNNING")?"QUEUED":"FAILED","{}",text(row,"state").equals("RUNNING")?"节点重启后重新排队":"媒体文件不可读取");
                else if(e.getStatusCode().value()==409)update(id,"FAILED","{}","节点配置或任务状态已变化，请重试");
                else if(Set.of(400,403,413,422).contains(e.getStatusCode().value()))update(id,"FAILED","{}",e.getReason()==null?"素材或模型不支持此任务":e.getReason());
                else update(id,text(row,"state"),text(row,"result_json"),"等待计算节点或可用空间");
                if(Set.of(429,502,503).contains(e.getStatusCode().value()))break;
            }catch(Exception e){update(id,text(row,"state"),text(row,"result_json"),"等待计算节点");}
        }
        } finally { ticking.set(false); }
    }
    private ObjectNode executionResult(Map<String,Object> row,JsonNode value){
        var result=value.isObject()?((ObjectNode)value).deepCopy():json.createObjectNode();
        try{
            var previous=json.readTree(text(row,"result_json"));
            var queue=previous==null?json.nullNode():previous.path("timingsMs").path("queue");
            if(queue.isNumber()){
                var timings=result.get("timingsMs") instanceof ObjectNode object?object:result.putObject("timingsMs");
                timings.put("queue",Math.max(0,queue.asLong()));
            }
        }catch(IOException ignored){}
        return result;
    }
    @Scheduled(fixedDelay=10000,initialDelay=15000,scheduler="audioScheduler")
    public void scheduledDiscover(){dispatch.background("discover",this::discover);}
    public void discover(){
        try {
            if(backgroundTasks!=null && !compute.configured()){
                backgroundTasks.skipPendingAudio("计算节点未配置或已停用");return;
            }
            var capability=compute.call(compute.connection(),"/internal/v1/status","GET",null);
            if(!capability.path("capabilities").toString().contains("media-probe")){
                if(backgroundTasks!=null)backgroundTasks.skipPendingAudio("节点未启用音轨探测阶段");return;
            }
            boolean fast=capability.path("capabilities").toString().contains("audio-role-fast");
            boolean classifier=capability.path("capabilities").toString().contains("audio-role-fast-classifier");
            boolean paired=capability.path("capabilities").toString().contains("audio-role-fast-v2");
            var candidates=mapper.candidates(scanCursor);
            var work=new LinkedHashSet<>(candidates);
            if(backgroundTasks!=null){
                var pending=backgroundTasks.pendingScopes(scopeBatchCursor,scopeSongCursor);
                if(pending.isEmpty()){scopeBatchCursor="";scopeSongCursor="";}
                for(var scope:pending){
                    scopeBatchCursor=text(scope,"batch_id");scopeSongCursor=text(scope,"song_id");
                    try{
                        if(fingerprint(scopeSongCursor).equals(text(scope,"fingerprint")))work.add(scopeSongCursor);
                        else backgroundTasks.resolveAudioScopes(scopeSongCursor,text(scope,"fingerprint"),"INTERRUPTED","扫描后的源媒体已变化");
                    }catch(ResponseStatusException missing){
                        if(missing.getStatusCode().value()!=404)throw missing;
                        backgroundTasks.resolveAudioScopes(scopeSongCursor,text(scope,"fingerprint"),"INTERRUPTED","关联歌曲已删除");
                    }
                }
            }
            for(String song:work){
                if(candidates.contains(song))scanCursor=song;
                discoverSong(song,fast,classifier,paired);
            }
            if(candidates.isEmpty()){
                scanCursor="";
                if(backgroundTasks!=null)backgroundTasks.finishDiscoveryRound(discoveryRound);
                discoveryRound=UUID.randomUUID().toString();
            }else scanCursor=candidates.getLast();
        } catch(Exception unavailable){
            if(backgroundTasks!=null)backgroundTasks.waitingForAudioNode();
            // Optional compute must not block scans or change their execution rules.
        }
    }
    private void discoverSong(String song,boolean fast,boolean classifier,boolean paired)throws IOException{
        String fp=fingerprint(song);
        var history=mapper.forSong(song,fp);
        var origins=backgroundTasks==null?List.<String>of():backgroundTasks.audioOrigins(song,fp);
        if(history.isEmpty()){submitAutomatic(song,"PROBE",fp,origins);return;}
        var probe=history.stream().filter(row->text(row,"kind").equals("PROBE")).findFirst();
        if(probe.isPresent()){
            attachExisting(probe.get(),song,fp,origins);
            if(Set.of("QUEUED","RUNNING").contains(text(probe.get(),"state")))return;
        }
        if(!fast){resolveDiscovery(song,fp,"SKIPPED","未启用快速识别阶段");return;}
        var confirmed=roles.role(song);
        if(confirmed!=null&&confirmed.path("state").asText().equals("CONFIRMED")){
            resolveDiscovery(song,fp,"SKIPPED","音轨角色已确认，无需快速识别");return;
        }
        var previous=history.stream().filter(row->text(row,"kind").equals("FAST")).findFirst();
        // Preserve the existing one-time retry rules when a classifier is installed or upgraded.
        boolean missing=previous.isPresent()&&text(previous.get(),"state").equals("SUCCEEDED")&&json.readTree(text(previous.get(),"result_json")).path("reason").asText().equals("FAST_MODEL_MISSING");
        boolean obsolete=false;
        if(paired&&previous.isPresent()&&text(previous.get(),"state").equals("SUCCEEDED")){
            var prior=json.readTree(text(previous.get(),"result_json"));
            obsolete=prior.path("state").asText().equals("UNKNOWN")&&!prior.path("evidence").path("analysisVersion").asText().equals("fast-vocal-v2");
        }
        if(previous.isPresent()&&!(missing&&classifier)&&!obsolete){
            attachExisting(previous.get(),song,fp,origins);return;
        }
        for(var row:history)if(text(row,"kind").equals("PROBE")&&text(row,"state").equals("SUCCEEDED")){
            if(json.readTree(text(row,"result_json")).path("streams").size()==2)submitAutomatic(song,"FAST",fp,origins);
            else resolveDiscovery(song,fp,"SKIPPED","非双音轨，无需快速识别");
            return;
        }
        resolveDiscovery(song,fp,probe.isPresent()?"FAILED":"SKIPPED",probe.isPresent()?"音轨探测未成功":"无需自动音轨处理");
    }
    private void submitAutomatic(String song,String kind,String fp,List<String> origins){
        if(backgroundTasks!=null&&origins.isEmpty())origins=backgroundTasks.automaticOrigin(discoveryRound,song,fp);
        submit(song,kind,"",origins);
    }
    private void attachExisting(Map<String,Object> job,String song,String fp,List<String> origins){
        if(backgroundTasks!=null&&!origins.isEmpty()&&!text(job,"id").isBlank())
            backgroundTasks.audioTransaction(()->{backgroundTasks.audioSubmitted(text(job,"id"),text(job,"kind"),song,fp,origins,false);return null;});
    }
    private void resolveDiscovery(String song,String fp,String state,String reason){
        if(backgroundTasks!=null)backgroundTasks.resolveAudioScopes(song,fp,state,reason);
    }
    private void invalidate(Map<String,Object> row,String reason){
        if(text(row,"state").equals("RUNNING"))try{compute.call(compute.connection(text(row,"node_origin")),"/internal/v1/audio-roles/tasks/"+text(row,"id")+"/cancel","POST",null);}catch(Exception ignored){}
        update(text(row,"id"),"FAILED","{}",reason);
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000,scheduler="audioScheduler")
    public void scheduledCollect(){dispatch.background("collect",this::collect);}
    public void collect(){
        if(!Files.isDirectory(root))return;
        try(var paths=Files.list(root)){
            for(Path path:paths.toList()){
                String name=path.getFileName().toString();
                if(!name.matches("[a-f0-9-]{36}\\.(m4a|flac|part)")||Files.getLastModifiedTime(path).toMillis()>System.currentTimeMillis()-86400000)continue;
                boolean remove=name.endsWith(".part");var job=mapper.job(name.substring(0,36));
                if(job==null)remove=true;
                else try{remove|=!text(job,"fingerprint").equals(fingerprint(text(job,"song_id")))||!text(job,"state").equals("SUCCEEDED");}
                catch(ResponseStatusException missing){if(missing.getStatusCode().value()==404)remove=true;else throw missing;}
                if(remove)Files.deleteIfExists(path);
            }
        }catch(IOException ignored){/* Try again without changing business state. */}
    }
    private synchronized void update(String id,String state,String result,String error){
        var latest=mapper.job(id);
        if(latest!=null&&text(latest,"state").equals("CANCELLED")&&!state.equals("CANCELLED"))return;
        if(latest!=null && state.equals(text(latest,"state")) && result.equals(text(latest,"result_json")) && error.equals(text(latest,"error")))return;
        if(mapper.updateJob(id,state,result,error,System.currentTimeMillis())>0 && !state.equals("RUNNING")){
            if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(state)){
                if(events!=null && latest!=null)events.publishEvent(new AudioCatalog.Changed(text(latest,"song_id")));
                if(dispatch!=null){dispatch.batches();dispatch.jobs(0);}
            }else if(dispatch!=null)dispatch.jobs(3000);
        }
    }
    private void checkReceiving(String id)throws java.io.InterruptedIOException {
        var latest=mapper.job(id);
        if(latest!=null&&text(latest,"state").equals("CANCELLED"))throw new java.io.InterruptedIOException("Audio task cancelled");
    }
    private void receive(Connection connection,String id,JsonNode result)throws IOException{
        long started=System.nanoTime();
        var stored=mapper.job(id);
        var specification=stored==null?AudioRecipe.legacy():recipe(stored);
        String filename=id+"."+specification.extension();
        if(!result.path("artifact").asText().equals(filename))throw fail(422,"伴奏产物无效");
        if(!specification.legacyDefault()&&(!result.path("separationProfile").asText().equals(specification.separationProfile())
                ||!result.path("modelId").asText().equals(specification.modelId())||!result.path("outputFormat").asText().equals(specification.outputFormat())
                ||!result.path("preview").isBoolean()||result.path("preview").asBoolean()!=specification.preview()))throw fail(422,"伴奏产物与任务方案不一致");
        Files.createDirectories(root);
        if(Files.getFileStore(root).getUsableSpace()<2L*1024*1024*1024)throw fail(507,"伴奏存储空间不足");
        Path temporary=root.resolve(id+".part");
        var response=compute.artifact(connection,"/internal/v1/audio-roles/tasks/"+id+"/artifact",null);
        try(var in=response.body();var out=Files.newOutputStream(temporary)){
            synchronized(this){checkReceiving(id);receiving.put(id,in);}
            if(response.statusCode()!=200)throw fail(response.statusCode()==404?422:502,"伴奏读取失败");
            java.security.MessageDigest digest;
            try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            byte[] buffer=new byte[65536];long size=0;int length;
            while((length=in.read(buffer))!=-1){size+=length;if(size>1024L*1024*1024)throw fail(413,"伴奏超过大小预算");digest.update(buffer,0,length);out.write(buffer,0,length);}
            if(size<1024||size!=result.path("size").asLong(-1)||!java.util.HexFormat.of().formatHex(digest.digest()).equals(result.path("sha256").asText()))throw fail(422,"伴奏校验失败");
        }catch(Exception e){Files.deleteIfExists(temporary);throw e;}
        finally { synchronized(this){receiving.remove(id);} }
        try {
            synchronized(this){
                checkReceiving(id);
                Files.move(temporary,root.resolve(filename),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            }
        } finally { Files.deleteIfExists(temporary); }
        if(result instanceof ObjectNode value)value.withObject("/timingsMs").put("transfer",(System.nanoTime()-started)/1_000_000d);
    }
    public void cancel(String song,String id){
        Map<String,Object> row;
        InputStream active;
        synchronized(this){
            row=require(song,id);if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(text(row,"state")))return;
            update(id,"CANCELLED","{}","");
            active=receiving.remove(id);
            try{Files.deleteIfExists(artifactPath(row));}catch(IOException ignored){}
        }
        if(active!=null)try{active.close();}catch(IOException ignored){}
        if(text(row,"state").equals("RUNNING"))try{compute.call(compute.connection(text(row,"node_origin")),"/internal/v1/audio-roles/tasks/"+id+"/cancel","POST",null);}catch(Exception ignored){}
    }
    Map<String,Object> require(String song,String id){
        var row=mapper.job(id);if(row==null||!text(row,"song_id").equals(song))throw fail(404,"任务不存在");return row;
    }
    public void source(String id,String token,String range,boolean head,HttpServletResponse response)throws IOException{
        var row=sourceRow(id,token);
        playback.play(text(row,"song_id"),range,head,response,()->sourceRow(id,token));
    }
    private Map<String,Object> sourceRow(String id,String token){
        var row=mapper.job(id);if(row==null||!text(row,"state").equals("RUNNING"))throw fail(404,"媒体授权不存在");
        var connection=compute.connection(text(row,"node_origin"));
        if(token==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),("Bearer "+connection.token()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw fail(401,"内部认证失败");
        if(!fingerprint(text(row,"song_id")).equals(text(row,"fingerprint")))throw fail(409,"源媒体已变化");
        return row;
    }
    public void accompaniment(String song,String id,String range,boolean head,HttpServletResponse response)throws IOException{
        var row=require(song,id);
        if(!text(row,"state").equals("SUCCEEDED")||!text(row,"kind").equals("SEPARATE")||!text(row,"fingerprint").equals(fingerprint(song)))throw fail(404,"伴奏版本已失效");
        playback.playDerived(root,artifactPath(row).getFileName().toString(),range,head,response);
    }
    private void preserveSelection(Map<String,Object> completed){
        String song=text(completed,"song_id"),fp=text(completed,"fingerprint");
        var selection=mapper.selection(song);
        if(selection!=null&&text(selection,"fingerprint").equals(fp))return;
        String chosen=text(completed,"id");
        // Preserve a pre-migration accompaniment before publishing a new alternative.
        for(var previous:mapper.forSong(song,fp))if(text(previous,"kind").equals("SEPARATE")&&text(previous,"state").equals("SUCCEEDED")&&recipe(previous).playable()&&Files.isRegularFile(artifactPath(previous))){chosen=text(previous,"id");break;}
        if(selection==null)mapper.insertSelection(song,fp,chosen,System.currentTimeMillis());
        else mapper.updateSelection(song,fp,chosen,System.currentTimeMillis());
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized ObjectNode activate(String song,String id){
        var row=require(song,id);
        if(!text(row,"state").equals("SUCCEEDED")||!text(row,"kind").equals("SEPARATE")||!text(row,"fingerprint").equals(fingerprint(song))||!recipe(row).playable()||!Files.isRegularFile(artifactPath(row)))throw fail(422,"请选择完整 AAC 伴奏；试听片段和 FLAC 导出不能设为播放版本");
        if(mapper.selection(song)==null)mapper.insertSelection(song,text(row,"fingerprint"),id,System.currentTimeMillis());
        else mapper.updateSelection(song,text(row,"fingerprint"),id,System.currentTimeMillis());
        if(events!=null)events.publishEvent(new AudioCatalog.Changed(song));
        return status(song);
    }
}
