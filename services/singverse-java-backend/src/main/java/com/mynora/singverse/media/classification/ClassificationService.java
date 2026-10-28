package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSongRepository;
import com.mynora.singverse.media.metadata.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ClassificationService {
    final ClassificationMapper mapper;
    final MetadataJson json;
    final MediaSongRepository songs;
    final ClassificationSettings settings;
    final TransactionTemplate tx;
    static final TypeReference<Map<String,Value>> VALUES=new TypeReference<>() {};
    static final TypeReference<Set<String>> SET=new TypeReference<>() {};
    static final TypeReference<List<Tag>> TAGS=new TypeReference<>() {};
    public ClassificationService(ClassificationMapper mapper,MetadataJson json,MediaSongRepository songs,ClassificationSettings settings,PlatformTransactionManager manager) {
        this.mapper=mapper;this.json=json;this.songs=songs;this.settings=settings;tx=new TransactionTemplate(manager);
    }
    static long number(Map<String,Object> r,String k) { return ((Number)r.get(k)).longValue(); }
    static String str(Map<String,Object> r,String k) { return Objects.toString(r.get(k),null); }
    static String id() { return UUID.randomUUID().toString(); }
    static void dimensions(Collection<String> values,boolean ai) {
        if(values==null || values.isEmpty() || values.stream().anyMatch(Objects::isNull) || !DIMENSIONS.containsAll(values) || ai && values.contains("CUSTOM")) throw bad("分类维度无效；AI 不能修改自定义标签");
    }
    static void page(int page,int size) { if(page<0 || page>1000000 || size<1 || size>100) throw bad("分页参数超出范围"); }
    public List<Tag> tags() { return mapper.tags().stream().map(r->new Tag(str(r,"id"),str(r,"dimension"),str(r,"name"),json.decode(r.get("aliases_json"),new TypeReference<List<String>>() {}),number(r,"active")==1,number(r,"revision"),number(r,"song_count"))).toList(); }
    public Tag saveTag(String tagId,SaveTag in) {
        if(in==null || !DIMENSIONS.contains(Objects.toString(in.dimension(),"")) || in.name()==null || in.name().isBlank() || in.name().length()>80 || in.name().chars().anyMatch(Character::isISOControl)
                || in.aliases()==null || in.aliases().size()>20 || in.aliases().stream().anyMatch(a->a==null || a.isBlank() || a.length()>80 || a.chars().anyMatch(Character::isISOControl))) throw bad("标签名称、维度或别名无效");
        return tx.execute(s->{ mapper.lock(); var all=tags();
            if(all.size()>=1000 && tagId==null) throw bad("词表最多支持 1000 个标签");
            if(all.stream().anyMatch(t->!t.id().equals(tagId) && t.dimension().equals(in.dimension()) && t.name().equalsIgnoreCase(in.name().trim()))) throw conflict("该维度已有同名标签");
            String target=tagId==null?id():tagId;
            if(tagId==null) mapper.insertTag(target,in.dimension(),in.name().trim(),json.encode(in.aliases()),in.active());
            else {
                Tag old=all.stream().filter(t->t.id().equals(tagId)).findFirst().orElseThrow(()->bad("标签不存在"));
                if(!old.dimension().equals(in.dimension())) throw bad("已有标签不能跨维度移动");
                if(mapper.updateTag(tagId,in.name().trim(),json.encode(in.aliases()),in.active(),in.revision())!=1) throw conflict("标签已变化，请刷新后保存");
            }
            return tags().stream().filter(t->t.id().equals(target)).findFirst().orElseThrow();
        });
    }
    State stored(String songId) { var r=mapper.state(songId); return r==null?new State(songId,0,new TreeMap<>()):new State(songId,number(r,"revision"),json.decode(r.get("state_json"),VALUES)); }
    public State state(String songId) { return tx.execute(s->{songs.get(songId);return stored(songId);}); }
    Input input(String songId) {
        var song=songs.get(songId); var values=SingleReviewService.current(song);
        @SuppressWarnings("unchecked") var artists=(List<String>)values.get("artists");
        return new Input(songId,Objects.toString(values.get("title"),""),artists,Objects.toString(values.get("album"),null),Objects.toString(values.get("releaseDate"),null),song.result().language().name(),song.result().versionType().name(),song.result().qualityTags());
    }
    String inputVersion(String songId) { return AuthCrypto.hash(json.encode(input(songId))+":"+MediaSongRepository.fingerprint(songs.get(songId))); }
    private void validateTags(Map<String,List<String>> values) {
        dimensions(values.keySet(),false); var vocabulary=tags();
        for(var entry:values.entrySet()) {
            if(entry.getValue()==null || entry.getValue().size()>8 || new HashSet<>(entry.getValue()).size()!=entry.getValue().size()) throw bad("每个维度最多 8 个不重复标签");
            for(String tag:entry.getValue()) if(vocabulary.stream().noneMatch(t->t.active() && t.id().equals(tag) && t.dimension().equals(entry.getKey()))) throw conflict("标签已停用、缺失或不属于当前维度，请重新选择");
        }
    }
    State write(State before,Map<String,Value> after,String origin,String taskId) {
        if(before.dimensions().equals(after)) return before;
        String data=json.encode(after);
        if(before.revision()==0) mapper.insertState(before.songId(),data);
        else if(mapper.updateState(before.songId(),before.revision(),data)!=1) throw conflict("分类已变化，请刷新后重试");
        mapper.clearAssignments(before.songId());
        after.forEach((dim,value)->value.tagIds().forEach(tag->mapper.assign(before.songId(),dim,tag)));
        mapper.insertHistory(id(),before.songId(),taskId,origin,json.encode(before.dimensions()),data,before.revision()+1,now());
        return new State(before.songId(),before.revision()+1,after);
    }
    private State receipt(String requestId,Object request,Supplier<State> action) {
        if(requestId==null || !requestId.matches("[a-fA-F0-9-]{36}")) throw bad("需要有效的提交标识");
        String hash=AuthCrypto.hash(json.encode(request)); var old=mapper.receipt(requestId);
        if(old!=null) { if(!hash.equals(old.get("request_hash"))) throw conflict("提交标识已用于其他操作"); return json.decode(old.get("response_json"),State.class); }
        State response=action.get(); mapper.receiptSave(requestId,hash,json.encode(response));return response;
    }
    public State change(String songId,Change change) {
        if(change==null || change.dimensions()==null || change.lockedDimensions()==null || !DIMENSIONS.containsAll(change.lockedDimensions())) throw bad("分类修改无效");
        return tx.execute(s->{mapper.lock();return receipt(change.requestId(),List.of(songId,change),()->{
            songs.get(songId);State current=stored(songId); if(current.revision()!=change.revision()) throw conflict("分类已变化，请刷新后保存");
            validateTags(change.dimensions()); Map<String,Value> next=new TreeMap<>(current.dimensions());
            change.dimensions().forEach((dim,ids)->next.put(dim,new Value(List.copyOf(ids),"MANUAL",change.lockedDimensions().contains(dim),true)));
            return write(current,next,"MANUAL",null);
        });});
    }
    List<String> targets(Start start) {
        if(start==null) throw bad("请选择歌曲和分类维度"); dimensions(start.dimensions(),true);
        if(start.autoDimensions()==null || !start.dimensions().containsAll(start.autoDimensions())) throw bad("自动应用维度必须属于任务维度");
        List<String> ids=start.all()?mapper.songIds(5001):start.songIds();
        if(ids==null || ids.isEmpty() || ids.size()>5000 || ids.stream().anyMatch(Objects::isNull)) throw bad("每批请选择 1～5000 首歌曲");
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }
    public Preview preview(Start start) { return tx.execute(s->{var ids=targets(start);return new Preview(ids.stream().limit(20).map(this::input).toList(),ids.size(),settings.read());}); }
    public Task start(Start start) {
        String task=tx.execute(s->{mapper.lock(); var config=settings.require();var ids=targets(start); var vocabulary=tags().stream().filter(Tag::active).toList();
            String taskId=id();
            mapper.insertTask(taskId,json.encode(config),json.encode(vocabulary),json.encode(start.dimensions()),json.encode(start.autoDimensions()),now());
            for(String song:ids) mapper.insertItem(id(),taskId,song,json.encode(input(song)),inputVersion(song),stored(song).revision(),now());
            return taskId;
        }); return task(task,0,20,null);
    }
    public List<Task> tasks(int page,int size) { page(page,size);return tx.execute(s->mapper.tasks(size,(long)page*size).stream().map(r->view(r,0,0,null)).toList()); }
    public Task task(String id,int page,int size,String status) { page(page,size); if(status!=null && !Set.of("PENDING","PROCESSING","REVIEW","APPLIED","UNCHANGED","FAILED","CANCELLED").contains(status)) throw bad("任务状态无效");return tx.execute(s->view(requireTask(id),page,size,status)); }
    Map<String,Object> requireTask(String id) {var r=mapper.task(id);if(r==null) throw bad("分类任务不存在");return r;}
    Task view(Map<String,Object> row,int page,int size,String status) {
        String task=str(row,"id");Map<String,Long> counts=new TreeMap<>();
        for(String state:List.of("PENDING","PROCESSING","REVIEW","APPLIED","UNCHANGED","FAILED","CANCELLED"))counts.put(state,0L);
        mapper.counts(task).forEach(c->counts.put(str(c,"status"),number(c,"n")));
        int total=counts.values().stream().mapToInt(Long::intValue).sum();
        List<Item> items=size==0?List.of():mapper.items(task,status,size,(long)page*size).stream().map(r->new Item(str(r,"id"),str(r,"song_id"),str(r,"status"),json.decode(r.get("input_json"),Input.class),number(r,"song_revision"),r.get("suggestion_json")==null?null:json.decode(r.get("suggestion_json"),Suggestion.class),str(r,"error"),stored(str(r,"song_id")))).toList();
        return new Task(task,str(row,"status"),json.decode(row.get("dimensions_json"),SET),json.decode(row.get("automatic_json"),SET),json.decode(row.get("config_json"),ModelConfig.class).model(),total,total-counts.get("PENDING").intValue()-counts.get("PROCESSING").intValue(),counts,items,page,size,mapper.itemCount(task,status),str(row,"created_at"),str(row,"updated_at"));
    }
    public Task control(String id,String action) {
        tx.executeWithoutResult(s->{mapper.lock();var task=requireTask(id);String state=str(task,"status");
            String next=switch(action) {
                case "pause"->{if(!state.equals("RUNNING"))throw conflict("只有运行中任务可以暂停");yield "PAUSED";}
                case "resume"->{if(!state.equals("PAUSED"))throw conflict("只有暂停任务可以继续");settings.require();yield mapper.itemCount(id,"PENDING")==0 && mapper.itemCount(id,"PROCESSING")==0 ? "COMPLETED" : "RUNNING";}
                case "cancel"->{if(!Set.of("RUNNING","PAUSED").contains(state))throw conflict("当前任务不能终止");mapper.cancelItems(id,now());yield "CANCELLED";}
                default->throw bad("任务操作无效");};
            mapper.taskState(id,next,str(task,"next_at"),now());
        });return task(id,0,20,null);
    }
    public Task retry(String taskId,String itemId) {
        tx.executeWithoutResult(s->{mapper.lock();var task=requireTask(taskId);var item=ownedItem(taskId,itemId);
            if(str(task,"status").equals("CANCELLED") || !Set.of("FAILED","REVIEW","UNCHANGED").contains(str(item,"status")))throw conflict("当前条目不可重新生成");
            settings.require();String song=str(item,"song_id");var current=input(song);
            mapper.retry(itemId,json.encode(current),inputVersion(song),stored(song).revision(),now());
            mapper.taskState(taskId,str(task,"status").equals("PAUSED")?"PAUSED":"RUNNING",str(task,"next_at"),now());
        }); return task(taskId,0,20,null);
    }
    Map<String,Object> ownedItem(String taskId,String id) {var item=mapper.item(id);if(item==null || !taskId.equals(item.get("task_id")))throw bad("歌曲条目不属于当前分类任务");return item;}
    void validateSuggestion(Map<String,Object> item,Map<String,Object> task,Suggestion suggestion) {
        Set<String> dims=json.decode(task.get("dimensions_json"),SET);
        if(suggestion==null || suggestion.dimensions()==null || !dims.equals(suggestion.dimensions().keySet()) || suggestion.reasons()==null || !dims.equals(suggestion.reasons().keySet()) || suggestion.newTagSuggestions()==null || suggestion.newTagSuggestions().size()>10)throw bad("模型输出结构无效");
        validateTags(suggestion.dimensions());
        List<Tag> frozen=json.decode(task.get("vocabulary_json"),TAGS);Map<String,Tag> current=new HashMap<>();tags().forEach(t->current.put(t.id(),t));
        suggestion.dimensions().forEach((dim,ids)->ids.forEach(id->{var old=frozen.stream().filter(t->t.id().equals(id) && t.dimension().equals(dim)).findFirst().orElseThrow(()->conflict("模型返回词表外标签"));var live=current.get(id);if(live==null || !live.active() || live.revision()!=old.revision())throw conflict("词表已变化，请重新建立任务");}));
        String song=str(item,"song_id");if(!str(item,"input_version").equals(inputVersion(song)))throw conflict("歌曲信息在生成后变化，请重新生成分类");
    }
    public State review(String taskId,String itemId,Review review) {
        if(review==null || review.dimensions()==null || review.dimensions().isEmpty())throw bad("请选择应用维度");
        return tx.execute(s->{mapper.lock();return receipt(review.requestId(),List.of(taskId,itemId,review),()->{
            var item=ownedItem(taskId,itemId);var task=requireTask(taskId);
            if(!str(item,"status").equals("REVIEW"))throw conflict("当前条目不是待审核状态");
            var suggestion=json.decode(item.get("suggestion_json"),Suggestion.class);validateSuggestion(item,task,suggestion);
            State current=stored(str(item,"song_id"));if(current.revision()!=review.revision())throw conflict("分类已变化，请刷新后审核");
            if(!suggestion.dimensions().keySet().containsAll(review.dimensions()))throw bad("审核维度不属于当前建议");
            Map<String,Value> next=new TreeMap<>(current.dimensions());
            for(String dim:review.dimensions()) {var old=next.get(dim);if(old!=null && old.locked())throw conflict("该维度已锁定，请在人工编辑中解锁后审核");var ids=suggestion.dimensions().get(dim);if(!ids.isEmpty()) next.put(dim,new Value(ids,"AI_REVIEWED",false,true));}
            State updated=write(current,next,"AI_REVIEWED",taskId);
            mapper.itemState(itemId,"APPLIED",json.encode(suggestion),"所选维度已确认，其余维度保留现状",updated.revision(),now());
            finish(taskId,0);return updated;
        });});
    }
    public List<History> history(String songId,int page,int size) {page(page,size);return mapper.history(songId,size,(long)page*size).stream().map(r->new History(str(r,"id"),str(r,"song_id"),str(r,"task_id"),str(r,"origin"),json.decode(r.get("before_json"),VALUES),json.decode(r.get("after_json"),VALUES),number(r,"after_revision"),number(r,"reverted")==1,str(r,"created_at"))).toList();}
    public State undo(String historyId,Undo input) {
        if(input==null)throw bad("撤回参数无效");
        return tx.execute(s->{mapper.lock();return receipt(input.requestId(),List.of(historyId,input),()->{
            var h=mapper.historyOne(historyId);if(h==null || number(h,"reverted")==1)throw conflict("历史不存在或已撤回");
            String song=str(h,"song_id");songs.get(song);State current=stored(song);
            if(current.revision()!=input.revision() || current.revision()!=number(h,"after_revision") || !current.dimensions().equals(json.decode(h.get("after_json"),VALUES)))throw conflict("分类或锁定状态已有后续修改，不能直接撤回");
            State restored=write(current,json.decode(h.get("before_json"),VALUES),"UNDO",str(h,"task_id"));mapper.reverted(historyId);return restored;
        });});
    }
    void finish(String taskId,int delay) {
        var task=requireTask(taskId);String state=str(task,"status");
        if(state.equals("RUNNING") && mapper.itemCount(taskId,"PENDING")==0 && mapper.itemCount(taskId,"PROCESSING")==0)state="COMPLETED";
        mapper.taskState(taskId,state,java.time.Instant.now().plusSeconds(delay).toString(),now());
    }
}
