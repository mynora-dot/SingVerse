package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.transcoding.TranscodeClient;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AudioProcessingService {
    public record Filter(String title,String artist,String sourceId,Integer streams,String state,String songId) {}
    public record Selection(Filter filter,List<String> ids,boolean all,String backend) {}
    private final AudioProcessingMapper mapper;
    private final AudioRoleMapper roles;
    private final AudioJobService jobs;
    private final MediaSongRepository songs;
    private final TranscodeClient compute;
    private final ObjectMapper json;
    public AudioProcessingService(AudioProcessingMapper mapper,AudioRoleMapper roles,AudioJobService jobs,MediaSongRepository songs,TranscodeClient compute,ObjectMapper json){
        this.mapper=mapper;this.roles=roles;this.jobs=jobs;this.songs=songs;this.compute=compute;this.json=json;
    }
    static String text(Map<String,Object> row,String key){return AudioJobService.text(row,key);}
    static String clean(String value){return value==null?"":value.trim();}
    static boolean contains(String value,String query){return clean(value).toLowerCase(Locale.ROOT).contains(clean(query).toLowerCase(Locale.ROOT));}
    static String title(Song song){return song.metadata()!=null&&!song.metadata().stale()&&song.metadata().fields().contains("title")?song.metadata().track().title():song.result().title();}
    static List<String> artists(Song song){return song.metadata()!=null&&!song.metadata().stale()&&song.metadata().fields().contains("artists")?song.metadata().track().artists():song.result().artists();}
    String category(JsonNode status){
        if(status.has("backingResource"))return "READY";
        for(var job:status.path("jobs"))if(job.path("kind").asText().equals("SEPARATE")&&Set.of("QUEUED","RUNNING").contains(job.path("state").asText()))return "PROCESSING";
        int count=status.path("streams").size();
        if(!status.has("streams"))return "UNPROBED";
        return count==1?"ELIGIBLE":"INAPPLICABLE";
    }
    ObjectNode row(Song song){
        var status=jobs.status(song.id());
        var value=json.createObjectNode().put("id",song.id()).put("title",title(song)).put("sourceId",song.sourceId()).put("sourceName",song.sourceName()).put("relativePath",song.relativePath());
        value.set("artists",json.valueToTree(artists(song)));value.set("audio",status);value.put("category",category(status));
        return value;
    }
    boolean matches(ObjectNode row,Filter filter){
        if(filter==null)return true;
        if(!clean(filter.songId()).isEmpty()&&!filter.songId().equals(row.path("id").asText()))return false;
        if(!contains(row.path("title").asText(),filter.title())||!contains(row.path("artists").toString(),filter.artist()))return false;
        if(filter.streams()!=null&&(!row.path("audio").has("streams")||filter.streams()!=row.path("audio").path("streams").size()))return false;
        String state=clean(filter.state());
        if(state.equals("FAILED"))return java.util.stream.StreamSupport.stream(row.path("audio").path("jobs").spliterator(),false).anyMatch(j->j.path("kind").asText().equals("SEPARATE")&&j.path("state").asText().equals("FAILED"));
        return state.isEmpty()||state.equals(row.path("category").asText());
    }
    void visit(Filter filter,Consumer<ObjectNode> consume){
        String cursor="";
        do{
            var ids=mapper.songIds(cursor,filter==null?null:filter.sourceId());
            if(ids.isEmpty())return;
            for(String id:ids){cursor=id;var value=row(songs.get(id));if(matches(value,filter))consume.accept(value);}
        }while(true);
    }
    public ObjectNode list(Filter filter,int page,int size){
        if(page<0||size<1||size>100)throw AudioJobService.fail(400,"分页参数无效");
        var value=json.createObjectNode().put("page",page).put("size",size);var items=value.putArray("items");long[] count={0};
        visit(filter,row->{long index=count[0]++;if(index>=(long)page*size&&index<(long)(page+1)*size)items.add(row);});
        return value.put("totalElements",count[0]);
    }
    @Transactional
    public synchronized ObjectNode preview(Selection input){
        if(input==null||!input.all()&&(input.ids()==null||input.ids().isEmpty()))throw AudioJobService.fail(400,"请选择歌曲");
        String backend=clean(input.backend());if(!Set.of("","cpu","cuda","rocm","mps","openvino").contains(backend))throw AudioJobService.fail(400,"未知推理后端");
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();mapper.insertBatch(id,backend,now);
        Consumer<ObjectNode> insert=row->{String category=row.path("category").asText(),song=row.path("id").asText();
            var item=new HashMap<String,Object>();item.put("batch",id);item.put("song",song);item.put("fingerprint",MediaSongRepository.fingerprint(songs.get(song)));
            item.put("title",row.path("title").asText());item.put("category",category);item.put("state",Set.of("UNPROBED","ELIGIBLE").contains(category)?"PENDING":"SKIPPED");item.put("now",now);mapper.insertItem(item);
        };
        if(input.all())visit(input.filter(),insert);else for(String song:new LinkedHashSet<>(input.ids()))insert.accept(row(songs.get(song)));
        return detail(id,0,50);
    }
    Map<String,Object> require(String id){var value=mapper.batch(id);if(value==null||value.isEmpty())throw AudioJobService.fail(404,"批量任务不存在");return value;}
    ObjectNode view(Map<String,Object> batch){
        String id=text(batch,"id");var value=json.createObjectNode().put("id",id).put("state",text(batch,"state")).put("backend",text(batch,"backend")).put("createdAt",((Number)batch.get("created_at")).longValue());
        long total=0;var counts=value.putObject("counts");var categories=value.putObject("categories");
        for(var count:mapper.counts(id)){long amount=((Number)count.get("amount")).longValue();total+=amount;String state=text(count,"state"),category=text(count,"category");counts.put(state,counts.path(state).asLong()+amount);categories.put(category,categories.path(category).asLong()+amount);}
        value.put("total",total);return value;
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
        if(!text(batch,"state").equals("PREVIEW"))return detail(id,0,50);
        var node=compute.call(compute.connection(),"/internal/v1/audio-roles/models","GET",null);
        String backend=text(batch,"backend");
        boolean ready=backend.isEmpty()?!node.path("selected").isNull()&&!node.path("selected").asText().isEmpty():java.util.stream.StreamSupport.stream(node.path("backends").spliterator(),false).anyMatch(b->b.path("id").asText().equals(backend)&&b.path("state").asText().equals("AVAILABLE"));
        if(!ready)throw AudioJobService.fail(422,"请先在节点与模型中安装模型并完成试跑");
        if(!node.path("resourceReady").asBoolean(true))throw AudioJobService.fail(422,"节点当前内存或磁盘空间不足，请查看节点与模型中的资源预算");
        mapper.start(id,System.currentTimeMillis());mapper.finish(id,System.currentTimeMillis());return detail(id,0,50);
    }
    public synchronized ObjectNode cancel(String id){
        require(id);mapper.cancel(id,System.currentTimeMillis());mapper.skipCancelled(id,System.currentTimeMillis());
        for(int page=0;;page++){
            var items=mapper.items(id,100,(long)page*100);if(items.isEmpty())break;
            for(var item:items)if(Set.of("PROBING","PROCESSING").contains(text(item,"state")))cancelMember(item);
        }
        return detail(id,0,50);
    }
    @Transactional
    public ObjectNode retry(String id,String backend){require(id);return preview(new Selection(null,mapper.failedSongs(id),false,backend));}
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
    void update(Map<String,Object> item,String state,String job,String error){mapper.updateItem(text(item,"batch_id"),text(item,"song_id"),state,job,error,System.currentTimeMillis());}
    @Scheduled(fixedDelay=3000,initialDelay=9000,scheduler="audioScheduler")
    public synchronized void tick(){
        var touched=new HashSet<String>();
        for(var item:mapper.pending()){
            String batch=text(item,"batch_id"),song=text(item,"song_id"),job=text(item,"job_id"),state=text(item,"state");touched.add(batch);
            try{
                if(text(item,"batch_state").equals("CANCELLED")){
                    cancelMember(item);continue;
                }
                if(!MediaSongRepository.fingerprint(songs.get(song)).equals(text(item,"fingerprint"))){
                    if(state.equals("PROCESSING")&&!job.isEmpty())jobs.cancel(song,job);
                    update(item,"FAILED",job,"源媒体已变化，请重新选择歌曲");continue;
                }
                if(!job.isEmpty()){
                    var execution=roles.job(job);if(execution==null||execution.isEmpty()){update(item,"FAILED",job,"歌曲任务已丢失");continue;}
                    String phase=text(execution,"state");
                    if(Set.of("FAILED","CANCELLED").contains(phase)){update(item,phase,job,text(execution,"error"));continue;}
                    if(!phase.equals("SUCCEEDED")){update(item,state,job,text(execution,"error"));continue;}
                    if(state.equals("PROCESSING")){update(item,"SUCCEEDED",job,"");continue;}
                }
                synchronized(jobs){
                    var status=jobs.status(song);String category=category(status);
                    if(category.equals("READY")||category.equals("PROCESSING")){update(item,"SKIPPED",null,category.equals("READY")?"已有伴奏":"已有其他分离任务");continue;}
                    if(category.equals("INAPPLICABLE")){update(item,"SKIPPED",null,"非单音轨，不生成独立伴奏");continue;}
                    boolean probe=category.equals("UNPROBED");
                    var result=jobs.submit(song,probe?"PROBE":"SEPARATE",probe?"":text(item,"backend"));
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
