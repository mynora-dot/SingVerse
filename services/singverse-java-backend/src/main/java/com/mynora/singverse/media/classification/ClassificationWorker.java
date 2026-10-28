package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import static com.mynora.singverse.media.classification.ClassificationService.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class ClassificationWorker {
    private final ClassificationService service;
    private final ClassificationModel model;
    private final boolean enabled;
    private final AtomicBoolean busy=new AtomicBoolean();
    private record Work(Map<String,Object> item,Map<String,Object> task,Input input,ModelConfig config) {}
    public ClassificationWorker(ClassificationService service,ClassificationModel model,@org.springframework.beans.factory.annotation.Value("${singverse.classification.worker-enabled:true}") boolean enabled) {this.service=service;this.model=model;this.enabled=enabled;}
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { service.tx.executeWithoutResult(s->{service.mapper.lock();service.mapper.recoverItems(now());service.mapper.recoverTasks(now());}); }
    @Scheduled(fixedDelay=1000,scheduler="classificationScheduler")
    public void tick() {if(enabled)processNext();}
    public void processNext() {
        if(!busy.compareAndSet(false,true))return;
        try {
            Work work=service.tx.execute(s->{service.mapper.lock();var item=service.mapper.next(now());if(item==null)return null;
                if(!service.settings.config().enabled())return null;
                var task=service.requireTask(str(item,"task_id"));
                service.mapper.itemState(str(item,"id"),"PROCESSING",null,null,number(item,"song_revision"),now());
                return new Work(item,task,service.json.decode(item.get("input_json"),Input.class),service.json.decode(task.get("config_json"),ModelConfig.class));});
            if(work==null)return;
            Suggestion suggestion=null;String failure=null;boolean limited=false;
            try {
                Set<String> dimensions=service.json.decode(work.task().get("dimensions_json"),SET);
                suggestion=model.generate(work.config(),work.input(),service.json.decode(work.task().get("vocabulary_json"),TAGS),dimensions);
                suggestion=facts(suggestion,work.input(),service.json.decode(work.task().get("vocabulary_json"),TAGS),dimensions);
            } catch(ClassificationModel.ModelFailure e) {failure=e.getMessage();limited=e.rateLimited();}
            catch(RuntimeException e) {failure="分类生成失败，未应用任何标签";}
            final Suggestion result=suggestion;final String error=failure;final int delay=limited?Math.max(60,work.config().intervalSeconds()):work.config().intervalSeconds();
            service.tx.executeWithoutResult(s->{service.mapper.lock();String taskId=str(work.task(),"id"),itemId=str(work.item(),"id"),song=work.input().songId();var task=service.requireTask(taskId);var item=service.mapper.item(itemId);
                service.mapper.cooldown(java.time.Instant.now().plusSeconds(delay).toString());
                if(!str(item,"status").equals("PROCESSING"))return;
                State current=service.stored(song);String state="REVIEW",note=error;
                if(error!=null)state="FAILED";
                else {
                    try {
                        service.validateSuggestion(work.item(),work.task(),result);
                        if(current.revision()!=number(work.item(),"song_revision"))note="分类在生成期间发生修改，未自动应用，请核对当前结果";
                        else if(str(task,"status").equals("RUNNING") && service.settings.config().enabled()) {
                            Set<String> automatic=service.json.decode(task.get("automatic_json"),SET);
                            Map<String,Value> next=new TreeMap<>(current.dimensions());boolean review=false;boolean any=false;
                            for(var entry:result.dimensions().entrySet()) {
                                if(entry.getValue().isEmpty())continue; any=true;
                                var old=current.dimensions().get(entry.getKey());
                                if(!automatic.contains(entry.getKey()) || old!=null && (old.locked() || old.confirmed())) {review=true;continue;}
                                next.put(entry.getKey(),new Value(entry.getValue(),"AI",false,false));
                            }
                            current=service.write(current,next,"AI",taskId);
                            state=review?"REVIEW":any?"APPLIED":"UNCHANGED";
                            if(review)note="部分维度需人工确认；人工标签或锁定维度已保留";
                        } else note="任务已暂停，建议已保存，未自动应用";
                    } catch(org.springframework.web.server.ResponseStatusException e) {note=e.getReason();}
                }
                service.mapper.cooldown(java.time.Instant.now().plusSeconds(delay).toString());
                service.mapper.itemState(itemId,state,result==null?null:service.json.encode(result),note,current.revision(),now());service.finish(taskId,delay);
            });
        } finally {busy.set(false);}
    }
    static Suggestion facts(Suggestion result,Input input,List<Tag> vocabulary,Set<String> dimensions) {
        if(result==null || result.dimensions()==null || result.reasons()==null)return result;
        Map<String,List<String>> values=new TreeMap<>(result.dimensions());Map<String,String> reasons=new TreeMap<>(result.reasons());
        if(dimensions.contains("LANGUAGE")) {
            String label=Map.of("MANDARIN","国语","CANTONESE","粤语","ENGLISH","英语","JAPANESE","日语","KOREAN","韩语","OTHER","其他").get(input.language());
            if(label!=null) {values.put("LANGUAGE",tag(vocabulary,"LANGUAGE",label));reasons.put("LANGUAGE","沿用文件名解析中的明确语种，未分析音频");}
        }
        if(dimensions.contains("DECADE")) {
            String date=input.releaseDate();String label=null;
            if(date!=null && date.matches("[12][0-9]{3}(-[0-9]{2})?(-[0-9]{2})?")) {int year=Integer.parseInt(date.substring(0,4));if(year<=java.time.Year.now().getValue())label=(year/10*10)+" 年代";}
            values.put("DECADE",label==null?List.of():tag(vocabulary,"DECADE",label));reasons.put("DECADE",label==null?"没有有效发行日期，保留未知":"按已确认发行日期归组，不代表首次发行或创作年代");
        }
        return new Suggestion(values,reasons,result.newTagSuggestions());
    }
    private static List<String> tag(List<Tag> vocabulary,String dim,String name) {return vocabulary.stream().filter(t->t.active() && t.dimension().equals(dim) && (t.name().equals(name)||t.aliases().contains(name))).limit(1).map(Tag::id).toList();}
}
