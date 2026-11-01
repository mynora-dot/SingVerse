package com.mynora.singverse.media.webdav.metadata;

import static com.mynora.singverse.media.webdav.metadata.WebDavAccess.*;
import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.metadata.MetadataMapper;
import com.mynora.singverse.compute.ComputeNodeSettings;
import com.mynora.singverse.infrastructure.compute.MediaTagsClient;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebDavMetadataService {
    private final WebDavMetadataMapper mapper;
    private final WebDavAccess access;
    private final MediaSourceRepository sources;
    private final MediaSongRepository songs;
    private final MetadataMapper metadata;
    private final ComputeNodeSettings compute;
    private final com.mynora.singverse.compute.ComputeNodeProbe probe;
    private volatile com.mynora.singverse.compute.ComputeNodeDtos.Check nodeCheck;
    private String checkedNode="";
    private long checkAfter;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final WebDavMetadataInput input;
    private final Path root;
    private final boolean enabled;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"webdav-metadata");t.setDaemon(true);return t;});
    private final AtomicBoolean working=new AtomicBoolean();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WebDavMetadataService(WebDavMetadataMapper mapper,WebDavAccess access,MediaSourceRepository sources,MediaSongRepository songs,
            MetadataMapper metadata,ComputeNodeSettings compute,com.mynora.singverse.compute.ComputeNodeProbe probe,ObjectMapper json,PlatformTransactionManager manager,WebDavMetadataInput input,
            @Value("${singverse.webdav.metadata-path:./data/metadata/webdav}") String path,
            @Value("${singverse.webdav.metadata-worker-enabled:true}") boolean enabled) {
        this.mapper=mapper;this.access=access;this.sources=sources;this.songs=songs;this.metadata=metadata;this.compute=compute;this.probe=probe;this.json=json;
        this.tx=new TransactionTemplate(manager);this.input=input;this.root=Path.of(path).toAbsolutePath().normalize();this.enabled=enabled;
    }
    @EventListener(ApplicationReadyEvent.class) public void recover() {
        tx.executeWithoutResult(s->{mapper.recover(args("after",Instant.now().plusSeconds(1800).toString()));mapper.recoverResources(Map.of());});
    }
    public void reset(String sourceId) {
        var a=args("sourceId",sourceId);mapper.clearSourceAssignments(a);mapper.clearSourceClassification(a);mapper.clearSourceCredits(a);mapper.clearSourceOverrides(a);
        mapper.sourceTasksDelete(args("sourceId",sourceId));mapper.resourcesDelete(args("sourceId",sourceId));mapper.sourceDelete(args("sourceId",sourceId));
    }
    public void prepare(Source source) {
        access.ensure(source);
        mapper.bootstrap(args("sourceId",source.id(),"binding",binding(source)));
    }
    /** Runs inside the scan publication transaction. Empty observations never publish deletions. */
    public void publish(Source source,String taskId,int total) {
        prepare(source);
        mapper.lockSource(args("sourceId",source.id()));
        String now=Instant.now().toString();
        var a=args("sourceId",source.id(),"taskId",taskId,"binding",binding(source),"now",now,"missingBefore",Instant.now().minusSeconds(1800).toString());
        if(total==0 && songs.count(source.id())>0) {
            mapper.emptyScan(a);
            mapper.counters(args("taskId",taskId,"added",0,"reused",0,"missing",songs.count(source.id()),"removed",0));
            return;
        }
        mapper.notEmpty(a);
        int added=mapper.newResources(a);
        mapper.observed(a);mapper.missing(a);
        int removed=mapper.archive(a);
        mapper.updateSongs(a);mapper.insertSongs(a);mapper.removeArchivedSongs(a);
        mapper.restorePending(a);mapper.queue(a);
        var presence=mapper.presence(a).getFirst();
        mapper.counters(args("taskId",taskId,"added",added,"reused",Math.max(0,total-added),"missing",number(presence,"missing"),"removed",removed));
    }
    public Map<String,Object> summary(String sourceId) {
        var source=sources.get(sourceId);requireDav(source);
        var config=access.ensure(source);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",number(config,"enabled")==1);out.put("paused",number(config,"paused")==1);
        out.put("accessGroup",text(config,"access_group"));out.put("privateOrigins",parse(text(config,"private_origins")));
        out.put("blockedUntil",config.get("blocked_until"));out.put("blockedReason",config.get("blocked_reason"));
        out.put("emptyScans",number(config,"empty_scans"));out.put("emptySince",config.get("empty_since"));
        out.put("canConfirmEmpty",number(config,"empty_scans")>=2 && !text(config,"empty_since").isEmpty() && !Instant.parse(text(config,"empty_since")).isAfter(Instant.now().minusSeconds(1800)));
        var counts=new TreeMap<String,Long>();mapper.counts(args("sourceId",sourceId)).forEach(row->counts.put(text(row,"state"),number(row,"total")));
        out.put("counts",counts);out.put("presence",java.util.Optional.ofNullable(mapper.presence(args("sourceId",sourceId)).getFirst()).orElse(Map.of("missing",0,"archived",0,"changed",0)));
        out.put("nodeStatus",nodeCheck==null?"NOT_CHECKED":nodeCheck.status());
        out.put("remoteReady",nodeCheck!=null&&nodeCheck.capabilities().contains("media-remote-tags"));
        out.put("nodeMessage",nodeCheck==null?"等待执行时检查计算节点能力":nodeCheck.status().equals("AVAILABLE")&&!nodeCheck.capabilities().contains("media-remote-tags")?"计算节点尚未开放远程元数据读取能力，请检查节点版本和 Java 回连地址；任务仍保留在队列。":nodeCheck.message());
        out.put("budget",access.budget(text(config,"access_group")));return out;
    }
    public Map<String,Object> configure(String sourceId,boolean enabled,boolean paused,String group,List<String> origins) {
        return tx.execute(s->{
            var source=sources.get(sourceId);requireDav(source);var config=access.ensure(source);
            mapper.lockSource(args("sourceId",sourceId));
            String value=group==null?text(config,"access_group"):group.trim();
            if(!value.matches("[A-Za-z0-9_-]{1,64}")) throw failure(400,"访问组仅支持 1–64 位字母、数字、下划线和连字符");
            List<String> checked=new ArrayList<>();
            if(origins==null) originsFrom(config,checked); else {
                if(origins.size()>16) throw failure(400,"最多配置 16 个私有代理地址");
                for(String origin:origins) checked.add(WebDavMetadataInput.checkedOrigin(origin));
            }
            mapper.configSave(args("sourceId",sourceId,"enabled",enabled?1:0,"paused",paused?1:0,"group",value,"origins",encode(checked)));
            prepare(source);mapper.queue(args("sourceId",sourceId,"now",Instant.now().toString()));
            return summary(sourceId);
        });
    }
    private void originsFrom(Map<String,Object> config,List<String> output) { for(var item:parse(text(config,"private_origins"))) output.add(item.asText()); }
    public Map<String,Object> detail(String songId) {
        songs.get(songId);var rows=mapper.resource(args("songId",songId));
        if(rows.isEmpty()) return Map.of("state","NOT_WEBDAV");
        var row=rows.getFirst();Map<String,Object> out=new LinkedHashMap<>();
        for(String field:List.of("state","generation","changed","missing_scans","bytes_read","request_count","elapsed_ms","updated_at")) out.put(field,row.get(field));
        out.put("result",parse(text(row,"result_json")));return out;
    }
    public Map<String,Object> refresh(List<String> ids,boolean deep) {
        if(ids==null||ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size()) throw failure(400,"请选择 1–100 首不同歌曲");
        return tx.execute(s->{
            List<Map<String,Object>> rows=new ArrayList<>();
            for(String id:ids) {
                var song=songs.get(id);var source=sources.get(song.sourceId());requireDav(source);prepare(source);
                var config=access.ensure(source);if(number(config,"paused")==1) throw failure(409,"请先继续提取，再执行单次读取");
                var resource=mapper.resource(args("songId",id)).getFirst();
                if(deep&&"RUNNING".equals(text(resource,"state")))throw failure(409,"歌曲正在提取，请等待完成后再选择深度提取");
                rows.add(resource);
            }
            for(var row:rows) {
                String id=text(row,"song_id");mapper.refresh(args("songId",id,"taskId",UUID.randomUUID().toString()));
                mapper.queueManual(args("songId",id,"now",Instant.now().toString()));
                var current=mapper.resource(args("songId",id)).getFirst();
                mapper.makeManual(args("songId",id,"generation",current.get("generation")));
                mapper.deep(args("songId",id,"generation",current.get("generation"),"deep",deep?1:0));
            }
            return Map.of("accepted",ids.size());
        });
    }
    public void confirmEmpty(String sourceId) {
        tx.executeWithoutResult(s->{
            mapper.lockSource(args("sourceId",sourceId));
            var summary=summary(sourceId);
            if(!Boolean.TRUE.equals(summary.get("canConfirmEmpty"))) throw failure(409,"需要间隔至少 30 分钟的两次完整空扫描");
            // A newer nonempty scan resets this state in the same database transaction.
            mapper.confirmEmpty(args("sourceId",sourceId,"now",Instant.now().toString()));
            mapper.removeArchivedSongs(args("sourceId",sourceId));
        });
    }
    @Scheduled(fixedDelay=5000,initialDelay=10000) public void tick() {
        if(!enabled||!working.compareAndSet(false,true))return;
        worker.execute(()->{try{runNext();}catch(Exception ignored){/* Persistent task is recovered/retried, never log upstream details. */}finally{working.set(false);}});
    }
    public void runNext() {
        var connection=compute.current();if(connection==null||connection.token().isBlank()){nodeCheck=new com.mynora.singverse.compute.ComputeNodeDtos.Check("DISABLED","未启用可用的计算节点，元数据任务继续保留",List.of(),0);return;}
        var pending=mapper.pending(args("now",Instant.now().toString(),"day",LocalDate.now(com.mynora.singverse.media.service.StandardScanCron.ZONE).toString()));
        if(pending.isEmpty())return;
        String nodeKey=AuthCrypto.hash(connection.origin()+"\0"+connection.token());
        if(!nodeKey.equals(checkedNode)||System.nanoTime()>=checkAfter) {
            nodeCheck=probe.check(connection);checkedNode=nodeKey;checkAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        }
        if(nodeCheck==null||!nodeCheck.capabilities().contains("media-remote-tags"))return;
        for(var task:pending) {
            String group=text(task,"access_group");var budget=access.budget(group);Instant now=Instant.now();
            String block=text(budget,"blocked_until");if(!block.isEmpty()&&Instant.parse(block).isAfter(now))continue;
            Boolean claimed=tx.execute(s->{
                if(mapper.fileStart(args("group",group,"after",now.plusSeconds(10).toString(),"now",now.toString()))!=1)return false;
                if(mapper.claim(args("id",task.get("id")))!=1)throw failure(409,"提取任务已被领取");
                mapper.running(args("songId",task.get("song_id"),"binding",task.get("binding"),"generation",task.get("generation")));return true;
            });
            if(!Boolean.TRUE.equals(claimed))continue;
            long started=System.nanoTime();
            try {
                boolean deep=number(task,"deep")==1;
                input.authorize(task,connection.token(),deep);
                var payload=args("reference",task.get("id"),"filename",task.get("relative_path"),"size",task.get("file_size"),"deep",deep);
                ComputeNodeSettings.checkNetwork(connection.origin());
                var request=HttpRequest.newBuilder(connection.origin().resolve("/internal/v1/media/remote-tags")).timeout(Duration.ofSeconds(deep?190:70))
                    .header("Authorization","Bearer "+connection.token()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
                var response=http.send(request,info->new com.mynora.singverse.media.metadata.BoundedResponse(4*1024*1024));
                if(response.statusCode()!=200)throw failure(503,"计算节点暂不可用");
                JsonNode result=json.readTree(response.body());
                if(!result.isObject()||!result.path("fields").isObject()||!result.path("tags").isObject())throw failure(503,"计算结果无效");
                complete(task,result);
            } catch(Exception error) { retry(task,"NETWORK_ERROR"); }
            finally {
                try { mapper.metrics(args("songId",task.get("song_id"),"binding",task.get("binding"),"generation",task.get("generation"),"bytes",input.bytes(text(task,"id")),"requests",input.requests(text(task,"id")),"elapsed",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started))); }
                finally { input.revoke(text(task,"id")); }
            }
            return;
        }
    }
    private void complete(Map<String,Object> task,JsonNode result) {
        if(deferIfPaused(task))return;
        String state=result.path("state").asText();
        if(Set.of("AUTH_REQUIRED","RATE_LIMITED","NETWORK_ERROR","UNSTABLE").contains(state)) { retry(task,state);return; }
        if(!Set.of("COMPLETE","EMPTY","PARTIAL","BUDGET_EXCEEDED","UNSUPPORTED","RANGE_UNSUPPORTED").contains(state))throw failure(503,"计算结果状态无效");
        var normalized=((com.fasterxml.jackson.databind.node.ObjectNode)result).deepCopy();
        String cover=normalized.path("cover").asText("");
        if(!cover.isEmpty()) {
            try {byte[] bytes=Base64.getDecoder().decode(cover);storeCover(bytes,normalized);}
            catch(Exception invalid) {normalized.putNull("cover");((com.fasterxml.jackson.databind.node.ObjectNode)normalized.path("fields")).put("cover","UNKNOWN");state="PARTIAL";}
        }
        JsonNode lyrics=normalized.get("lyrics");
        if(lyrics!=null&&!lyrics.isNull()) {
            byte[] bytes=encode(lyrics).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(bytes.length>262144)throw failure(503,"歌词结果超限");
            String hash=store(bytes,".lyrics.json"); normalized.put("lyricsAsset",hash);
        }
        var tags=json.convertValue(normalized.path("tags"),MediaTagsClient.Tags.class);
        if(tags.title()!=null&&tags.title().length()>500||tags.album()!=null&&tags.album().length()>500||tags.artists().size()>32||tags.artists().stream().anyMatch(v->v==null||v.length()>200)||tags.releaseDate()!=null&&tags.releaseDate().length()>32)throw failure(503,"标签结果无效");
        normalized.put("state",state);
        final String terminal=state;
        tx.executeWithoutResult(s->{
            mapper.lockSource(args("sourceId",task.get("source_id")));
            var rows=mapper.resource(args("songId",task.get("song_id")));if(rows.isEmpty())return;
            var current=rows.getFirst();
            if(deferIfPaused(task))return;
            if(number(current,"generation")!=number(task,"generation")||!text(current,"binding").equals(text(task,"binding"))||current.get("archived_at")!=null||!binding(sources.get(text(task,"source_id"))).equals(text(task,"binding"))) {
                mapper.finishTask(args("id",task.get("id"),"state","SUPERSEDED","error",null,"availableAt",Instant.now().toString()));return;
            }
            mapper.finishResource(args("songId",task.get("song_id"),"binding",task.get("binding"),"generation",task.get("generation"),"state",terminal,
                "result",encode(normalized),"tags",encode(tags),"version",result.path("extractorVersion").asText("remote-tags-1"),"bytes",input.bytes(text(task,"id")),"requests",input.requests(text(task,"id")),"elapsed",result.path("elapsedMs").asLong(),"now",Instant.now().toString()));
            mapper.applyTags(args("songId",task.get("song_id"),"tags",encode(tags)));
            mapper.finishTask(args("id",task.get("id"),"state",terminal,"error",null,"availableAt",Instant.now().toString()));
        });
    }
    private boolean deferIfPaused(Map<String,Object> task) {
        var rows=mapper.resource(args("songId",task.get("song_id")));
        if(rows.isEmpty())return true;
        var current=rows.getFirst();
        if(current.get("archived_at")!=null) {
            mapper.finishTask(args("id",task.get("id"),"state","SUPERSEDED","error",null,"availableAt",Instant.now().toString()));return true;
        }
        if((number(current,"enabled")==1||number(task,"manual")==1)&&number(current,"paused")==0&&text(current,"access_group").equals(text(task,"access_group"))&&text(current,"private_origins").equals(text(task,"private_origins")))return false;
        tx.executeWithoutResult(s->{
            mapper.finishTask(args("id",task.get("id"),"state","QUEUED","error","PAUSED","availableAt",Instant.now().plusSeconds(30).toString()));
            mapper.resourceState(args("songId",task.get("song_id"),"binding",task.get("binding"),"generation",task.get("generation"),"state","PENDING"));
        });
        return true;
    }
    private void retry(Map<String,Object> task,String reason) {
        if(deferIfPaused(task))return;
        long attempt=number(task,"attempts")+1;
        boolean again=attempt<3&&!reason.equals("UNSTABLE");
        String after=Instant.now().plusSeconds(attempt==1?1800:21600).toString();
        if(reason.equals("AUTH_REQUIRED"))access.block(text(task,"source_id"),true,Instant.now());
        if(reason.equals("RATE_LIMITED"))access.block(text(task,"source_id"),false,Instant.now().plusSeconds(21600));
        tx.executeWithoutResult(s->{
            mapper.finishTask(args("id",task.get("id"),"state",again?"QUEUED":reason,"error",reason,"availableAt",after));
            // Preserve prior good data on transient failure or unstable reads.
            mapper.resourceState(args("songId",task.get("song_id"),"binding",task.get("binding"),"generation",task.get("generation"),"state",again?"PENDING":reason));
        });
    }
    void validateShared(JsonNode snapshot) {
        if(!snapshot.isObject()||!snapshot.path("tags").isObject())throw failure(400,"共享元数据格式无效");
        var tags=json.convertValue(snapshot.path("tags"),MediaTagsClient.Tags.class);
        if(tags.title()!=null&&tags.title().length()>500||tags.album()!=null&&tags.album().length()>500||tags.releaseDate()!=null&&tags.releaseDate().length()>32||tags.artists().size()>32||tags.artists().stream().anyMatch(v->v==null||v.length()>200)||encode(snapshot).length()>4*1024*1024)throw failure(400,"共享元数据超过限制");
        if(snapshot.hasNonNull("lyrics")&&encode(snapshot.get("lyrics")).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw failure(400,"歌词超过限制");
        if(snapshot.has("coverData")&&snapshot.path("coverData").asText().length()>2666668)throw failure(400,"封面超过限制");
        if(snapshot.has("aliases")&&(!snapshot.path("aliases").isArray()||snapshot.path("aliases").size()>32||java.util.stream.StreamSupport.stream(snapshot.path("aliases").spliterator(),false).anyMatch(v->!v.isTextual()||v.asText().length()>500)))throw failure(400,"别名超过限制");
    }
    void installShared(String id,JsonNode snapshot) {
        validateShared(snapshot);var song=songs.get(id);mapper.bootstrap(args("sourceId",song.sourceId(),"binding",binding(sources.get(song.sourceId())),"songId",id));var result=json.createObjectNode();
        for(String key:List.of("tags","technical","lyrics","fields","evidence","extractorVersion","aliases"))if(snapshot.has(key))result.set(key,snapshot.get(key));
        if(snapshot.hasNonNull("coverData"))try{storeCover(Base64.getDecoder().decode(snapshot.path("coverData").asText()),result);}catch(Exception error){throw failure(400,"共享封面无效");}
        if(snapshot.hasNonNull("lyrics"))result.put("lyricsAsset",store(encode(snapshot.get("lyrics")).getBytes(java.nio.charset.StandardCharsets.UTF_8),".lyrics.json"));
        result.put("state","IMPORTED");result.put("origin","SHARED");result.put("importedAt",Instant.now().toString());
        var row=mapper.resource(args("songId",id)).getFirst();String tags=encode(json.convertValue(snapshot.path("tags"),MediaTagsClient.Tags.class));
        mapper.finishResource(args("songId",id,"binding",row.get("binding"),"generation",row.get("generation"),"state","IMPORTED","result",encode(result),"tags",tags,"version","shared-index-1","now",Instant.now().toString()));
        mapper.applyTags(args("songId",id,"tags",tags));
    }
    private void storeCover(byte[] bytes,com.fasterxml.jackson.databind.node.ObjectNode result)throws Exception {
        // Reuse the existing decoder validation; only JPEG/PNG and bounded dimensions are accepted.
        com.mynora.singverse.media.metadata.MetadataCoverService.validate(bytes);
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);
        if(output.size()>2_000_000)throw new IllegalArgumentException();
        result.put("cover",store(output.toByteArray(),".image"));
    }
    String store(byte[] bytes,String suffix) {
        String hash=AuthCrypto.hash(Base64.getEncoder().encodeToString(bytes));
        try{Files.createDirectories(root);Path target=root.resolve(hash+suffix);if(!Files.exists(target)){Path temp=Files.createTempFile(root,"metadata-",".tmp");try{Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}return hash;}
        catch(java.io.IOException error){throw failure(503,"元数据衍生目录不可写");}
    }
    public byte[] sharedCover(String hash) {
        if(!hash.matches("[a-f0-9]{64}"))throw failure(400,"Invalid asset hash");
        try{return Files.readAllBytes(root.resolve(hash+".image"));}catch(java.io.IOException error){throw failure(409,"Missing shared asset; import earlier parts first");}
    }
    public String installSharedCover(String encoded) {
        try{byte[] bytes=Base64.getDecoder().decode(encoded);com.mynora.singverse.media.metadata.MetadataCoverService.validate(bytes);if(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))==null)throw failure(400,"Invalid cover");return store(bytes,".image");}catch(Exception error){throw failure(400,"Invalid shared cover");}
    }
    public byte[] cover(String songId) {
        var detail=detail(songId);JsonNode result=(JsonNode)detail.get("result");String hash=result==null?"":result.path("cover").asText("");
        if(!hash.matches("[a-f0-9]{64}"))throw failure(404,"没有内嵌封面");
        try{return Files.readAllBytes(root.resolve(hash+".image"));}catch(java.io.IOException error){throw failure(404,"封面文件不可用");}
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000)public void prune() {
        tx.executeWithoutResult(s->{var a=args("before",Instant.now().minusSeconds(30L*86400).toString());metadata.lock();mapper.pruneMetadata(a);mapper.pruneOverrides(a);mapper.pruneAssignments(a);mapper.pruneClassification(a);mapper.pruneCredits(a);mapper.pruneTasks(a);mapper.pruneResources(a);});
        if(Files.isDirectory(root))try(var files=Files.list(root)) {
            for(var file:files.filter(f->!Files.isSymbolicLink(f)).limit(1000).toList()) {
                if(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minusSeconds(30L*86400)))continue;
                String name=file.getFileName().toString();
                if(name.matches("[a-f0-9]{64}\\.(image|lyrics\\.json)") && mapper.assetReferences(args("pattern","%"+name.substring(0,64)+"%"))==0)Files.deleteIfExists(file);
                else if(name.startsWith("metadata-")&&name.endsWith(".tmp"))Files.deleteIfExists(file);
            }
        }catch(java.io.IOException ignored){}
    }
    private JsonNode parse(String value) { try{return value.isBlank()?json.nullNode():json.readTree(value);}catch(Exception error){return json.nullNode();} }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception error){throw failure(503,"元数据无法保存");} }
    private static void requireDav(Source source) {if(!"WEBDAV".equals(source.type()))throw failure(400,"仅适用于 WebDAV 媒体库");}
    @jakarta.annotation.PreDestroy public void close(){worker.shutdownNow();}
}
