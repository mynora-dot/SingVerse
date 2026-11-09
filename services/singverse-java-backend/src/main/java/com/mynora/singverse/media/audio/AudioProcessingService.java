package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AudioProcessingService {
    public record Filter(String title,String artist,String sourceId,Integer streams,String state,String songId) {}
    public record Selection(Filter filter,List<String> ids,boolean all,String backend,String separationProfile,String modelId,String outputFormat) {
        public Selection(Filter filter,List<String> ids,boolean all,String backend){this(filter,ids,all,backend,null,null,null);}
        AudioRecipe recipe(){return new AudioRecipe(separationProfile,modelId,outputFormat,false,null);}
    }
    private final AudioProcessingMapper mapper;
    private final AudioRoleMapper roles;
    private final AudioJobService jobs;
    private final MediaSongRepository songs;
    private final TranscodeClient compute;
    private final ObjectMapper json;
    private final AudioCatalog catalog;
    private final TransactionTemplate transaction;
    private final AtomicBoolean preparing = new AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired private AudioDispatch dispatch;
    @jakarta.annotation.PostConstruct void initializeDispatch(){dispatch.registerBatches(this::tick);}
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "audio-batch-preparation"); thread.setDaemon(true); return thread;
    });
    public AudioProcessingService(AudioProcessingMapper mapper,AudioRoleMapper roles,AudioJobService jobs,MediaSongRepository songs,TranscodeClient compute,ObjectMapper json,AudioCatalog catalog,PlatformTransactionManager transactions){
        this.mapper=mapper;this.roles=roles;this.jobs=jobs;this.songs=songs;this.compute=compute;this.json=json;this.catalog=catalog;this.transaction=new TransactionTemplate(transactions);
    }
    static String text(Map<String,Object> row,String key){return AudioJobService.text(row,key);}
    static String clean(String value){return value==null?"":value.trim();}
    static boolean contains(String value,String query){return clean(query).isEmpty()||clean(value).toLowerCase(Locale.ROOT).contains(clean(query).toLowerCase(Locale.ROOT));}
    static String title(Song song){return song.metadata()!=null&&!song.metadata().stale()&&song.metadata().fields().contains("title")?song.metadata().track().title():song.result().title();}
    static List<String> artists(Song song){return song.metadata()!=null&&!song.metadata().stale()&&song.metadata().fields().contains("artists")?song.metadata().track().artists():song.result().artists();}
    static String category(JsonNode status){
        if(status.has("backingResource"))return "READY";
        if(status.path("state").asText().equals("CONFIRMED")&&!status.path("backingId").asText("").isEmpty())return "READY";
        for(var job:status.path("jobs"))if(job.path("kind").asText().equals("SEPARATE")&&Set.of("QUEUED","RUNNING").contains(job.path("state").asText()))return "PROCESSING";
        int count=status.path("streams").size();
        if(!status.has("streams"))return "UNPROBED";
        return count==1?"ELIGIBLE":"INAPPLICABLE";
    }
    static String category(JsonNode status,AudioRecipe recipe){
        if(recipe.legacyDefault()&&!status.has("versions"))return category(status);
        if(status.path("state").asText().equals("CONFIRMED")&&!status.path("backingId").asText("").isEmpty())return "READY";
        for(var version:status.path("versions"))if(recipe.separationProfile().equals(version.path("separationProfile").asText(AudioRecipe.LEGACY))
                &&recipe.outputFormat().equals(version.path("outputFormat").asText("aac"))&&!version.path("preview").asBoolean())return "READY";
        for(var job:status.path("jobs"))if(job.path("kind").asText().equals("SEPARATE")&&Set.of("QUEUED","RUNNING").contains(job.path("state").asText())
                &&recipe.separationProfile().equals(job.path("separationProfile").asText(AudioRecipe.LEGACY))
                &&recipe.outputFormat().equals(job.path("outputFormat").asText("aac"))&&!job.path("preview").asBoolean())return "PROCESSING";
        if(!status.has("streams"))return "UNPROBED";
        return status.path("streams").size()==1?"ELIGIBLE":"INAPPLICABLE";
    }
    ObjectNode row(Song song, ObjectNode status) {
        var value=json.createObjectNode().put("id",song.id()).put("title",title(song)).put("sourceId",song.sourceId()).put("sourceName",song.sourceName()).put("relativePath",song.relativePath());
        value.put("coverAvailable",song.metadata()!=null&&!song.metadata().stale()&&song.metadata().fields().contains("cover")&&song.metadata().track().coverUrl()!=null);
        value.set("artists",json.valueToTree(artists(song)));value.set("audio",status);value.put("category",category(status));
        return value;
    }
    public ObjectNode list(Filter filter,int page,int size){
        if(page<0||size<1||size>100)throw AudioJobService.fail(400,"分页参数无效");
        var snapshot=catalog.current();
        var value=json.createObjectNode().put("page",page).put("size",size)
                .put("generation",snapshot.generation()).put("building",catalog.building());
        var items=value.putArray("items");
        long count=0, start=(long)page*size;
        var ids=new ArrayList<String>();
        for(var summary:snapshot.songs())if(summary.matches(filter)){
            if(count>=start && ids.size()<size)ids.add(summary.id());
            count++;
        }
        var pageSongs=songs.byIds(ids);
        var statuses=catalog.statuses(pageSongs);
        var byId=new HashMap<String,Song>();pageSongs.forEach(song->byId.put(song.id(),song));
        for(String id:ids)if(byId.containsKey(id))items.add(row(byId.get(id),statuses.get(id)));
        return value.put("totalElements",count);
    }
    public ObjectNode preview(Selection input){
        if(input==null||!input.all()&&(input.ids()==null||input.ids().isEmpty()))throw AudioJobService.fail(400,"请选择歌曲");
        String backend=clean(input.backend());if(backend.equals("auto"))backend="";
        var specification=input.recipe();specification.validateBackend(backend);
        var snapshot=catalog.current();
        var selected=new ArrayList<AudioCatalog.Summary>();
        if(input.all()){
            for(var song:snapshot.songs())if(song.matches(input.filter()))selected.add(song);
        }else for(String id:new LinkedHashSet<>(input.ids())){
            var song=snapshot.byId().get(id);
            if(song==null)throw AudioJobService.fail(409,"歌曲索引已变化，请刷新列表后重试");
            selected.add(song);
        }
        if(selected.isEmpty())throw AudioJobService.fail(400,"没有符合条件的歌曲");
        if(!preparing.compareAndSet(false,true))throw AudioJobService.fail(429,"正在准备其他批次，请稍后重试");
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        try{
            String chosen=backend;
            transaction.executeWithoutResult(status->{mapper.insertBatch(id,chosen,now,selected.size());mapper.setRecipe(id,specification.view(json).toString());});
            preparation.submit(()->prepare(id,List.copyOf(selected)));
        }catch(RuntimeException failure){
            preparing.set(false);
            if(failure instanceof RejectedExecutionException)
                mapper.preparationFailed(id,"准备任务被中断，请重新预览",System.currentTimeMillis());
            throw failure;
        }
        return detail(id,0,50);
    }
    void prepare(String id,List<AudioCatalog.Summary> selected){
        try{
            var specification=AudioRecipe.stored(json,require(id).get("recipe_json"));
            for(int offset=0;offset<selected.size();offset+=200){
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                var batch=selected.subList(offset,Math.min(selected.size(),offset+200));
                Boolean continued=transaction.execute(status->{
                    long now=System.currentTimeMillis();
                    if(mapper.lockPreparing(id,now)!=1)return false;
                    var current=new HashMap<String,Song>();
                    songs.byIds(batch.stream().map(AudioCatalog.Summary::id).toList()).forEach(song->current.put(song.id(),song));
                    var statuses=catalog.statuses(new ArrayList<>(current.values()));
                    var entries=new ArrayList<Map<String,Object>>();
                    for(var song:batch){
                        var item=new HashMap<String,Object>();String category=statuses.containsKey(song.id())?category(statuses.get(song.id()),specification):song.category();
                        var actual=current.get(song.id());
                        boolean unchanged=actual!=null&&MediaSongRepository.fingerprint(actual).equals(song.fingerprint());
                        item.put("batch",id);item.put("song",song.id());item.put("fingerprint",song.fingerprint());
                        item.put("title",Objects.toString(song.title(),""));item.put("category",category);
                        item.put("state",!unchanged?"FAILED":Set.of("UNPROBED","ELIGIBLE").contains(category)?"PENDING":"SKIPPED");
                        item.put("error",unchanged?"":"源媒体已变化，请重新选择歌曲");item.put("now",now);entries.add(item);
                    }
                    mapper.insertItems(entries);mapper.prepared(id,batch.size(),now);return true;
                });
                if(!Boolean.TRUE.equals(continued))return;
            }
            mapper.ready(id,System.currentTimeMillis());
        }catch(RuntimeException failure){
            try{mapper.preparationFailed(id,"准备任务被中断，请重新预览",System.currentTimeMillis());}catch(RuntimeException ignored){}
        }finally{preparing.set(false);}
    }
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void recoverPreparations(){mapper.recoverPreparing(System.currentTimeMillis());}
    @jakarta.annotation.PreDestroy public void close(){preparation.shutdownNow();}
    Map<String,Object> require(String id){var value=mapper.batch(id);if(value==null||value.isEmpty())throw AudioJobService.fail(404,"批量任务不存在");return value;}
    ObjectNode view(Map<String,Object> batch){
        String id=text(batch,"id");var value=json.createObjectNode().put("id",id).put("state",text(batch,"state")).put("backend",text(batch,"backend")).put("createdAt",((Number)batch.get("created_at")).longValue());
        value.setAll(AudioRecipe.stored(json,batch.get("recipe_json")).view(json));
        long total=0;var counts=value.putObject("counts");var categories=value.putObject("categories");
        for(var count:mapper.counts(id)){long amount=((Number)count.get("amount")).longValue();total+=amount;String state=text(count,"state"),category=text(count,"category");counts.put(state,counts.path(state).asLong()+amount);categories.put(category,categories.path(category).asLong()+amount);}
        value.put("total",batch.get("selection_total") instanceof Number n?n.longValue():total)
                .put("prepared",batch.get("prepared_count") instanceof Number n?n.longValue():total)
                .put("selectionTotal",batch.get("selection_total") instanceof Number n?n.longValue():total)
                .put("error",text(batch,"preparation_error"));return value;
    }
    public ObjectNode detail(String id,int page,int size){
        if(page<0||size<1||size>100)throw AudioJobService.fail(400,"分页参数无效");
        var value=view(require(id));var items=value.putArray("items");
        for(var row:mapper.items(id,size,(long)page*size)){var item=(ObjectNode)json.valueToTree(row);try{item.set("execution",json.readTree(text(row,"execution")));}catch(java.io.IOException ignored){item.remove("execution");}items.add(item);}
        value.put("page",page).put("size",size);return value;
    }
    public ObjectNode batches(int page,int size){
        if(page<0||size<1||size>100)throw AudioJobService.fail(400,"分页参数无效");
        var value=json.createObjectNode().put("totalElements",mapper.batchCount());var items=value.putArray("items");for(var batch:mapper.batches(size,(long)page*size))items.add(view(batch));return value;
    }
    public synchronized ObjectNode start(String id){
        var batch=require(id);
        if(text(batch,"state").equals("PREPARING"))throw AudioJobService.fail(409,"批次仍在准备中，请稍后提交");
        if(!text(batch,"state").equals("PREVIEW"))return detail(id,0,50);
        var node=compute.call(compute.connection(),"/internal/v1/audio-roles/models","GET",null);
        String backend=text(batch,"backend");
        var specification=AudioRecipe.stored(json,batch.get("recipe_json"));
        boolean ready=specification.ready(node,backend);
        if(!ready)throw AudioJobService.fail(422,"请先在性能节点配置中安装模型并完成试跑");
        if(specification.legacyDefault()&&!node.path("resourceReady").asBoolean(true))throw AudioJobService.fail(422,"节点当前内存或磁盘空间不足，请查看性能节点配置中的资源预算");
        mapper.start(id,System.currentTimeMillis());mapper.finish(id,System.currentTimeMillis());
        if(dispatch!=null)dispatch.batches();return detail(id,0,50);
    }
    public synchronized ObjectNode cancel(String id){
        var batch=require(id);mapper.cancel(id,System.currentTimeMillis());mapper.skipCancelled(id,System.currentTimeMillis());
        if(Set.of("PREPARING","PREVIEW").contains(text(batch,"state")))return detail(id,0,50);
        for(int page=0;;page++){
            var items=mapper.items(id,100,(long)page*100);if(items.isEmpty())break;
            for(var item:items)if(Set.of("PROBING","PROCESSING").contains(text(item,"state")))cancelMember(item);
        }
        return detail(id,0,50);
    }
    public ObjectNode retry(String id,String backend){var batch=require(id);var specification=AudioRecipe.stored(json,batch.get("recipe_json"));return preview(new Selection(null,mapper.failedSongs(id),false,backend,specification.separationProfile(),specification.modelId(),specification.outputFormat()));}
    public synchronized void cancelItem(String id,String song){
        require(id);
        // Pages are bounded at the API; cancellation locates the exact snapshot member through its key.
        var item=mapper.item(id,song);if(item==null||item.isEmpty())throw AudioJobService.fail(404,"批次中没有此歌曲");
        cancelMember(item);mapper.finish(id,System.currentTimeMillis());
    }
    void cancelMember(Map<String,Object> item){
        String job=text(item,"job_id");
        if(text(item,"state").equals("PROCESSING")&&!job.isEmpty()){
            synchronized(jobs){
                var execution=roles.job(job);
                if(execution!=null&&text(execution,"state").equals("SUCCEEDED")){update(item,"SUCCEEDED",job,"");return;}
                jobs.cancel(text(item,"song_id"),job);
            }
        }
        update(item,"CANCELLED",job,"");
    }
    void update(Map<String,Object> item,String state,String job,String error){
        if(state.equals(text(item,"state"))&&clean(job).equals(text(item,"job_id"))&&clean(error).equals(text(item,"error")))return;
        mapper.updateItem(text(item,"batch_id"),text(item,"song_id"),state,job,error,System.currentTimeMillis());
    }
    @Scheduled(fixedDelay=3000,initialDelay=9000,scheduler="audioScheduler")
    public void scheduledTick(){dispatch.batches();}
    public synchronized void tick(){
        var touched=new HashSet<String>();
        var pending=mapper.pending();
        var current=new HashMap<String,Song>();
        songs.byIds(pending.stream().map(item->text(item,"song_id")).distinct().toList()).forEach(song->current.put(song.id(),song));
        var executions=new HashMap<String,Map<String,Object>>();
        var jobIds=pending.stream().map(item->text(item,"job_id")).filter(id->!id.isEmpty()).distinct().toList();
        if(!jobIds.isEmpty())roles.jobsByIds(jobIds).forEach(job->executions.put(text(job,"id"),job));
        for(var item:pending){
            String batch=text(item,"batch_id"),song=text(item,"song_id"),job=text(item,"job_id"),state=text(item,"state");touched.add(batch);
            try{
                if(text(item,"batch_state").equals("CANCELLED")){
                    cancelMember(item);continue;
                }
                if(!current.containsKey(song)){
                    if(!job.isEmpty())jobs.cancel(song,job);
                    update(item,"FAILED",job,"歌曲已删除");continue;
                }
                if(!MediaSongRepository.fingerprint(current.get(song)).equals(text(item,"fingerprint"))){
                    if(state.equals("PROCESSING")&&!job.isEmpty())jobs.cancel(song,job);
                    update(item,"FAILED",job,"源媒体已变化，请重新选择歌曲");continue;
                }
                if(!job.isEmpty()){
                    var execution=executions.get(job);if(execution==null||execution.isEmpty()){update(item,"FAILED",job,"歌曲任务已丢失");continue;}
                    String phase=text(execution,"state");
                    if(Set.of("FAILED","CANCELLED").contains(phase)){update(item,phase,job,text(execution,"error"));continue;}
                    if(!phase.equals("SUCCEEDED")){update(item,state,job,text(execution,"error"));continue;}
                    if(state.equals("PROCESSING")){update(item,"SUCCEEDED",job,"");continue;}
                }
                synchronized(jobs){
                    var status=jobs.status(song);var specification=AudioRecipe.stored(json,item.get("recipe_json"));String category=category(status,specification);
                    if(category.equals("READY")||category.equals("PROCESSING")){update(item,"SKIPPED",null,category.equals("READY")?"已有伴奏":"已有其他分离任务");continue;}
                    if(category.equals("INAPPLICABLE")){update(item,"SKIPPED",null,"非单音轨，不生成独立伴奏");continue;}
                    boolean probe=category.equals("UNPROBED");
                    var origins=List.of(com.mynora.singverse.background.BackgroundTaskService.audioBatchId(text(item,"batch_id")));
                    var result=probe||specification.legacyDefault()
                            ?jobs.submit(song,probe?"PROBE":"SEPARATE",probe?"":text(item,"backend"),origins)
                            :jobs.submit(song,"SEPARATE",text(item,"backend"),origins,specification);
                    update(item,probe?"PROBING":"PROCESSING",result.path("id").asText(),"");
                }
            }catch(org.springframework.web.server.ResponseStatusException error){
                if(error.getStatusCode().value()==404)update(item,"FAILED",job,"歌曲已删除");
                else update(item,state,job,"等待计算节点或运行环境");
            }
        }
        for(String id:touched)mapper.finish(id,System.currentTimeMillis());
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000,scheduler="audioScheduler")
    @Transactional
    public void collect(){long before=System.currentTimeMillis()-86400000;mapper.collectPreviewItems(before);mapper.collectPreviews(before);}
}
