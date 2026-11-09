package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.filename.FilenameParseResult;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LxDownloadService {
    static final int MAX_SCRIPT=9_000_000;
    static final String PURPOSE="singverse:lx-source:v1";
    private final LxMapper mapper;
    private final LxTargetService target;
    private final LxNetwork network;
    private final LxRuntime runtime;
    private final LxCatalog catalog;
    private final LxCoverService covers;
    private final ObjectMapper json;
    private final AuthCrypto crypto;
    private final MediaSourceRepository sources;
    private final FilenameParserConfigService profiles;
    private final TransactionTemplate transaction;
    private final ExecutorService downloads=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"lx-download");t.setDaemon(true);return t;});
    private final ExecutorService catalogReads=Executors.newFixedThreadPool(5,r->{var t=new Thread(r,"lx-catalog");t.setDaemon(true);return t;});
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private volatile boolean ready=false,closing=false;
    public LxDownloadService(LxMapper mapper,LxTargetService target,LxNetwork network,LxRuntime runtime,LxCatalog catalog,LxCoverService covers,
            ObjectMapper json,AuthCrypto crypto,MediaSourceRepository sources,FilenameParserConfigService profiles,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.mapper=mapper;this.target=target;this.network=network;this.runtime=runtime;this.catalog=catalog;this.covers=covers;this.json=json;
        this.crypto=crypto;this.sources=sources;this.profiles=profiles;transaction=new TransactionTemplate(transactions);
    }
    public Map<String,Object> status() {
        var position=target.status();var settings=mapper.settings();Map<String,Object> script=null;
        String id=str(settings,"active_script_id");if(!id.isEmpty()){var row=mapper.script(args("id",id));if(row!=null&&number(row,"deleted")==0)script=publicScript(row);}
        return args("target",position,"activeSource",script,"ready",Boolean.TRUE.equals(position.get("ready"))&&script!=null,
                "platforms",PLATFORMS,"activeBatches",mapper.activeBatches().size());
    }
    public List<Map<String,Object>> scripts(){return mapper.scripts().stream().map(this::publicScript).toList();}
    private Map<String,Object> publicScript(Map<String,Object> row){return args("id",row.get("id"),"name",row.get("name"),"version",row.get("version"),"environment",row.get("environment"),"createdAt",row.get("created_at"),"capabilities",read(str(row,"capabilities_json")),"active",str(row,"id").equals(str(mapper.settings(),"active_script_id")));}
    public Map<String,Object> importScript(SourceInput input) {
        if(input==null)throw bad("请选择音源文件或填写 HTTPS 地址");
        boolean fromUrl=input.url()!=null&&!input.url().isBlank();String script=input.script(),address=null;
        if(fromUrl) {
            if(script!=null&&!script.isBlank())throw bad("文件与链接请选择一种导入方式");
            address=input.url().trim();if(address.length()>4096)throw bad("音源链接过长");
            var reply=network.request(address,"GET",Map.of(),null,MAX_SCRIPT,true,"lx-import-"+UUID.randomUUID());
            if(reply.status()!=200)throw bad("无法读取音源链接，请检查地址是否有效");script=utf8(reply.bytes());
        }
        if(script==null||script.isBlank()||script.getBytes(StandardCharsets.UTF_8).length>MAX_SCRIPT)throw bad("音源需为 UTF-8 JavaScript 文件，最大 9 MB");
        if(script.charAt(0)=='\ufeff')script=script.substring(1);
        String environment=input.environment()==null?"mobile":input.environment();if(!Set.of("mobile","desktop").contains(environment))throw bad("兼容模式请选择 mobile 或 desktop");
        var capabilities=runtime.inspect(script,environment);
        String name=input.name()==null||input.name().isBlank()?header(script,"name","我的音源"):input.name().trim();
        if(name.length()>100||name.matches("(?s).*[\\x00-\\x1f].*"))throw bad("音源名称最多 100 个字，不能包含控制字符");
        String version=header(script,"version","未标注");String hash=AuthCrypto.hash(script);
        String secret=crypto.encrypt(encode(args("script",script,"url",address)),PURPOSE);
        String finalName=name;return transaction.execute(tx->{
            mapper.lockSettings();var same=mapper.sameScript(args("hash",hash,"environment",environment));
            if(same!=null){mapper.deleteScript(args("id",same.get("id"),"deleted",0));return publicScript(mapper.script(args("id",same.get("id"))));}
            String id=UUID.randomUUID().toString();mapper.insertScript(args("id",id,"name",finalName,"version",version,"environment",environment,"hash",hash,"secret",secret,"capabilities",encode(capabilities),"now",now()));
            return publicScript(mapper.script(args("id",id)));
        });
    }
    public Map<String,Object> activate(String id) {
        var row=script(id);var capabilities=runtime.inspect(body(row),str(row,"environment"));
        transaction.executeWithoutResult(tx->{mapper.lockSettings();script(id);mapper.activate(args("id",id));});
        return args("id",id,"capabilities",capabilities);
    }
    public Map<String,Object> test(String id){var row=script(id);return args("capabilities",runtime.inspect(body(row),str(row,"environment")),"message","音源初始化成功，实际歌曲可用性以下载结果为准");}
    public void deleteScript(String id){transaction.executeWithoutResult(tx->{mapper.lockSettings();script(id);if(mapper.activeScript(args("id",id))>0)throw conflict("此版本还有下载任务，请先结束或取消任务");if(id.equals(str(mapper.settings(),"active_script_id")))mapper.activate(args("id",null));mapper.deleteScript(args("id",id,"deleted",1));});}
    private Map<String,Object> script(String id){var row=mapper.script(args("id",id));if(row==null||number(row,"deleted")!=0)throw bad("音源不存在，请重新选择");return row;}
    private String body(Map<String,Object> row){return read(crypto.decrypt(str(row,"secret_encrypted"),PURPOSE)).path("script").asText();}
    Map<String,List<String>> capabilities(Map<String,Object> row){try{return json.readValue(str(row,"capabilities_json"),new TypeReference<>(){});}catch(Exception e){throw conflict("音源能力信息无效，请重新导入");}}
    private Map<String,Object> requireSource(){target.require();String id=str(mapper.settings(),"active_script_id");if(id.isEmpty())throw conflict("请先导入并启用音源");return script(id);}
    public Map<String,Object> search(String query,String platform,int page) {
        if(query==null||query.isBlank()||query.length()>100||page<1||page>100)throw bad("请输入 1–100 个字的歌名或歌手，页码最多 100");
        var enabled=capabilities(requireSource());List<String> platforms;
        if(platform==null||platform.equals("all"))platforms=PLATFORMS.keySet().stream().filter(enabled::containsKey).toList();else {if(!enabled.containsKey(platform))throw bad("当前音源不支持该平台，请切换平台或音源");platforms=List.of(platform);}
        var result=new ArrayList<Track>();var errors=new ArrayList<Map<String,Object>>();var warnings=new ArrayList<String>();boolean more=false;
        // Platform failures are isolated. Provider results retain their own identities and versions.
        var requests=new LinkedHashMap<String,CompletableFuture<LxCatalog.Page>>();
        for(String code:platforms)requests.put(code,CompletableFuture.supplyAsync(()->catalog.search(code,query,page),catalogReads));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        for(var request:requests.entrySet()){String code=request.getKey();try{var response=request.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);remember(response.tracks());result.addAll(response.tracks());more|=response.hasMore();if(!response.warning().isEmpty())warnings.add(PLATFORMS.get(code)+"："+response.warning());}catch(Exception e){request.getValue().cancel(true);errors.add(args("platform",code,"message",e instanceof ExecutionException&&e.getCause() instanceof Exception cause?explanation(cause):"平台读取超时，请重试或切换平台"));}}
        return args("tracks",result.stream().map(this::view).toList(),"errors",errors,"warnings",warnings,"page",page,"hasMore",more);
    }
    public Map<String,Object> preview(PreviewInput input) {
        if(input==null)throw bad("请粘贴分享链接");if(input.page()<1||input.page()>500)throw bad("每次最多读取 10000 首歌曲，请拆分后导入");var enabled=capabilities(requireSource());var ref=catalog.reference(input.url());
        if(!enabled.containsKey(ref.platform()))throw bad("当前音源不支持该链接的平台，请更换音源");
        var response=catalog.collection(ref,input.page());remember(response.tracks());
        return args("platform",response.platform(),"kind",response.kind(),"name",response.name(),"coverUrl",covers.reference(response.coverUrl()),"tracks",response.tracks().stream().map(this::view).toList(),"page",response.page(),"total",response.total(),"hasMore",response.hasMore(),"warning",response.warning());
    }
    public TrackView detail(String id) {var enabled=capabilities(requireSource());var row=cached(id);if(!enabled.containsKey(str(row,"platform")))throw bad("当前音源不支持该平台");var track=catalog.detail(track(str(row,"track_json")));remember(List.of(track));return view(track);}
    public List<TrackView> trackStates(List<String> ids) {
        if(ids==null||ids.size()>1000)throw bad("一次最多读取 1000 首歌曲状态");
        return ids.stream().distinct().map(this::cached).map(row->view(track(str(row,"track_json")))).toList();
    }
    private Map<String,Object> cached(String id){if(id==null||!id.matches("[a-f0-9]{64}"))throw bad("歌曲选择无效，请重新搜索");var row=mapper.track(args("id",id));if(row==null)throw bad("歌曲预览已过期，请重新搜索");return row;}
    public TrackView view(Track track) {
        String song=null;for(var asset:mapper.assetsForTrack(args("trackKey",track.id())))if(present(asset)){song=str(asset,"song_id");break;}
        return new TrackView(track.id(),track.platform(),track.title(),track.artists(),track.album(),track.durationMs(),covers.reference(track.coverUrl()),song!=null,mapper.activeTrack(args("trackKey",track.id()))>0,song);
    }
    private boolean present(Map<String,Object> asset){try{Path root=Path.of(str(asset,"source_path"));Path path=root.resolve(str(asset,"relative_path")).normalize();return path.startsWith(root)&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().startsWith(root)&&Files.size(path)==number(asset,"file_size")&&Files.getLastModifiedTime(path).toInstant().toString().equals(str(asset,"modified_at"));}catch(Exception e){return false;}}
    private void remember(List<Track> tracks){transaction.executeWithoutResult(tx->{mapper.lockSettings();for(var track:tracks){mapper.deleteTrack(args("id",track.id()));mapper.insertTrack(args("id",track.id(),"platform",track.platform(),"remoteId",track.remoteId(),"json",encode(track),"now",now()));}});}
    public Map<String,Object> create(BatchInput input) {
        if(input==null||input.trackIds()==null||input.trackIds().isEmpty()||input.trackIds().size()>1000)throw bad("每批请选择 1–1000 首歌曲");
        String quality=input.quality()==null?"auto":input.quality();if(!quality.equals("auto")&&!QUALITIES.contains(quality))throw bad("音质无效");
        return transaction.execute(tx->{
            mapper.lockSettings();var destination=target.require();var script=requireSource();var enabled=capabilities(script);String sourceId=str(destination,"source_id");
            var tracks=new ArrayList<Track>();var duplicates=new LinkedHashSet<String>();
            for(String id:new LinkedHashSet<>(input.trackIds())) {
                if(id==null||!id.matches("[a-f0-9]{64}"))throw bad("歌曲选择无效，请重新搜索");
                var row=mapper.track(args("id",id));if(row==null)throw bad("歌曲预览已过期，请重新搜索或读取链接");Track track=track(str(row,"track_json"));
                if(!enabled.containsKey(track.platform())||!quality.equals("auto")&&!enabled.get(track.platform()).contains(quality))throw bad("所选歌曲的平台或音质不受当前音源支持");
                var active=mapper.activeItem(args("activeKey",activeKey(sourceId,track.id(),quality)));
                if(active!=null)duplicates.add(str(active,"batch_id"));else tracks.add(track);
            }
            if(tracks.isEmpty())return args("batch",batch(duplicates.iterator().next()),"merged",input.trackIds().size(),"relatedBatchIds",duplicates);
            String id=UUID.randomUUID().toString(),name=input.name()==null||input.name().isBlank()?"歌曲下载 · "+tracks.size()+" 首":input.name().trim();if(name.length()>200)throw bad("批次名称最多 200 个字");
            mapper.insertBatch(args("id",id,"name",name,"sourceId",sourceId,"path",destination.get("source_path"),"scriptId",script.get("id"),"quality",quality,"now",now()));
            for(Track track:tracks)mapper.insertItem(args("id",UUID.randomUUID().toString(),"batchId",id,"trackKey",track.id(),"trackJson",encode(track),"quality",quality,"activeKey",activeKey(sourceId,track.id(),quality),"status","QUEUED","message","","songId",null,"now",now()));
            settle(id);return args("batch",batch(id),"merged",input.trackIds().size()-tracks.size(),"relatedBatchIds",duplicates);
        });
    }
    private static String activeKey(String source,String track,String quality){return source+":"+track+":"+quality;}
    public Map<String,Object> batches(int page,int size){bounds(page,size);return args("items",mapper.batches(args("size",size,"offset",(long)page*size)).stream().map(this::batchView).toList(),"total",mapper.batchCount(),"page",page,"size",size);}
    public Map<String,Object> batch(String id){var row=mapper.batch(args("id",id));if(row==null)throw bad("下载记录不存在");return batchView(row);}
    private Map<String,Object> batchView(Map<String,Object> row){var counts=new HashMap<String,Long>();for(var count:mapper.counts(args("id",row.get("id"))))counts.put(str(count,"status"),number(count,"amount"));return args("id",row.get("id"),"name",row.get("name"),"sourceId",row.get("source_id"),"path",row.get("source_path"),"scriptId",row.get("script_id"),"quality",row.get("quality"),"status",row.get("status"),"createdAt",row.get("created_at"),"updatedAt",row.get("updated_at"),"counts",counts,"total",counts.values().stream().mapToLong(Long::longValue).sum());}
    public Map<String,Object> items(String id,int page,int size){batch(id);bounds(page,size);return args("items",mapper.items(args("id",id,"size",size,"offset",(long)page*size)).stream().map(row->args("id",row.get("id"),"track",view(track(str(row,"track_json"))),"status",row.get("status"),"requestedQuality",row.get("requested_quality"),"resolvedQuality",row.get("resolved_quality"),"receivedBytes",row.get("received_bytes"),"totalBytes",row.get("total_bytes"),"songId",row.get("song_id"),"message",row.get("message"))).toList(),"total",mapper.itemCount(args("id",id)),"page",page,"size",size);}
    public void cancel(String id){transaction.executeWithoutResult(tx->{mapper.lockSettings();batch(id);mapper.cancel(args("id",id,"now",now()));for(String item:running){var row=mapper.item(args("id",item));if(row!=null&&id.equals(str(row,"batch_id")))network.cancel(item);}mapper.updateBatch(args("id",id,"status","CANCELLING","now",now(),"started",null,"finished",null));settle(id);});}
    public Map<String,Object> retry(String id){var old=mapper.batch(args("id",id));if(old==null)throw bad("下载记录不存在");var rows=mapper.retryItems(args("id",id));if(rows.isEmpty())throw bad("此批次没有可重试的歌曲");remember(rows.stream().map(r->track(str(r,"track_json"))).toList());String name=str(old,"name");return create(new BatchInput(rows.stream().map(r->str(r,"track_key")).toList(),str(old,"quality"),name.substring(0,Math.min(190,name.length()))+" · 重试"));}
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // An interrupted transfer is always re-resolved on retry; private partial files are never published.
        for(var row:mapper.transferring())try{var item=mapper.item(args("id",row.get("id")));Files.deleteIfExists(temporary(checkedRoot(item),str(item,"id")));}catch(Exception ignored){}
        for(var row:mapper.recovering()) {
            String id=str(row,"id");try{var item=mapper.item(args("id",id));MediaIndexCoordinator.locked(str(item,"source_id"),()->{recoverFile(item);return null;});}catch(Exception e){handleFailure(id,e);}
        }
        transaction.executeWithoutResult(tx->{mapper.interrupt(args("now",now()));for(var batch:mapper.activeBatches())settle(str(batch,"id"));});ready=true;
    }
    @Scheduled(fixedDelay=1000)
    public synchronized void pump() {
        if(!ready||closing||running.size()>=2)return;
        for(var pending:mapper.recovering()) {
            if(running.size()>=2)break;String id=str(pending,"id");if(!running.add(id))continue;
            downloads.submit(()->{var item=mapper.item(args("id",id));try{MediaIndexCoordinator.locked(str(item,"source_id"),()->{recoverFile(item);return null;});}catch(Exception e){handleFailure(id,e);}finally{running.remove(id);transaction.executeWithoutResult(tx->{mapper.lockSettings();settle(str(item,"batch_id"));});}});
        }
        for(var candidate:mapper.queued()) {
            String id=str(candidate,"id");if(running.size()>=2)break;if(!running.add(id))continue;
            try {
                boolean claimed=Boolean.TRUE.equals(transaction.execute(tx->{mapper.lockSettings();if(mapper.claim(args("id",id,"now",now()))!=1)return false;settle(str(candidate,"batch_id"));return true;}));
                if(!claimed){running.remove(id);continue;}
                downloads.submit(()->{try{download(id);}catch(Exception e){handleFailure(id,e);}finally{running.remove(id);transaction.executeWithoutResult(tx->{mapper.lockSettings();settle(str(candidate,"batch_id"));});}});
            }catch(RuntimeException error){running.remove(id);throw error;}
        }
    }
    private void download(String id) {
        var item=mapper.item(args("id",id));Track track=track(str(item,"track_json"));var script=script(str(item,"script_id"));
        Path root=checkedRoot(item),temporary=temporary(root,id);String requested=str(item,"requested_quality"),chosen=null,url=null;
        List<String> qualities=requested.equals("auto")?capabilities(script).getOrDefault(track.platform(),List.of()):List.of(requested);
        String sourceBody=body(script);
        for(String quality:qualities) {
            if(cancelled(id))return;
            var asset=mapper.asset(args("sourceId",item.get("source_id"),"trackKey",track.id(),"quality",quality));
            if(asset!=null&&present(withPath(asset,root))){finish(id,"SKIPPED",quality,str(asset,"relative_path"),str(asset,"song_id"),str(asset,"content_hash"),"已在曲库，保留已有文件");return;}
            try{url=runtime.resolve(sourceBody,str(script,"environment"),track,quality);chosen=quality;break;}
            catch(LxRuntime.LxFailure e){if(!requested.equals("auto")||!qualityUnavailable(e.getMessage()))throw e;}
        }
        if(url==null)throw new LxRuntime.LxFailure("音源未能提供这些音质，请更换音源或歌曲版本");
        if(!reserveQuality(item,chosen,root))return;
        final String resolved=chosen;
        try {
            Files.deleteIfExists(temporary);
            var file=network.download(url,temporary,id,()->cancelled(id),(received,total)->mapper.progress(args("id",id,"received",received,"total",total,"now",now())));
            if(cancelled(id)){Files.deleteIfExists(temporary);return;}
            MediaIndexCoordinator.locked(str(item,"source_id"),()->{
                publish(item,track,resolved,temporary,file);return null;
            });
        }catch(IOException error){throw new LxNetwork.LxNetworkFailure("无法写入下载文件，请检查挂载、目录权限和剩余空间");}
        finally{try{if(!"PUBLISHING".equals(str(mapper.item(args("id",id)),"status")))Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    private void publish(Map<String,Object> item,Track track,String quality,Path temporary,LxNetwork.Download file) {
        String id=str(item,"id");Path root=checkedRoot(item);
        transaction.executeWithoutResult(tx->{
            mapper.lockSettings();if(cancelled(id))return;
            // AUTO and explicit-quality jobs may resolve to the same format. De-duplicate at publication.
            var prior=mapper.asset(args("sourceId",item.get("source_id"),"trackKey",track.id(),"quality",quality));
            if(prior!=null&&present(withPath(prior,root))){finish(id,"SKIPPED",quality,str(prior,"relative_path"),str(prior,"song_id"),str(prior,"content_hash"),"已在曲库，保留已有文件");return;}
            String relative=filename(root,track,quality,file.extension());String songId=songId(str(item,"source_id"),relative);
            finish(id,"PUBLISHING",quality,relative,songId,file.hash(),"正在加入曲库");
        });
        var publishing=mapper.item(args("id",id));if(!"PUBLISHING".equals(str(publishing,"status")))return;
        Path destination=root.resolve(str(publishing,"relative_path"));
        try {
            // A hard link is an atomic, no-replace publication on the same filesystem. Delete the private name afterwards.
            // Fall back to a no-replace move for filesystems without hard-link support.
            if(!root.toRealPath().equals(root)||Files.isSymbolicLink(temporary.getParent()))throw new IOException();
            try{Files.createLink(destination,temporary);Files.delete(temporary);}catch(UnsupportedOperationException|FileSystemException e){if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw e;Files.move(temporary,destination);}
            recoverFile(mapper.item(args("id",id)));
        }catch(IOException e){throw new LxNetwork.LxNetworkFailure("完整文件发布失败，请检查下载目录挂载与权限，已有文件已保留");}
    }
    private boolean reserveQuality(Map<String,Object> item,String quality,Path root) {
        String id=str(item,"id"),key=activeKey(str(item,"source_id"),str(item,"track_key"),quality);
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(6);
        while(!cancelled(id)) {
            int state=Objects.requireNonNull(transaction.execute(tx->{
                mapper.lockSettings();if(cancelled(id))return -1;
                var asset=mapper.asset(args("sourceId",item.get("source_id"),"trackKey",item.get("track_key"),"quality",quality));
                if(asset!=null&&present(withPath(asset,root))){finish(id,"SKIPPED",quality,str(asset,"relative_path"),str(asset,"song_id"),str(asset,"content_hash"),"已在曲库，保留已有文件");return -1;}
                var owner=mapper.qualityOwner(args("key",key));if(owner!=null&&!id.equals(str(owner,"id")))return 0;
                return mapper.reserveQuality(args("id",id,"key",key,"quality",quality,"now",now()));
            }));
            if(state!=0)return state>0;
            if(System.nanoTime()>deadline)throw conflict("相同歌曲仍在下载，请稍后重试");
            try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();throw conflict("下载已中断，请重试");}
        }
        return false;
    }
    private void recoverFile(Map<String,Object> item) {
        String id=str(item,"id");Path root=checkedRoot(item),path=safeFile(root,str(item,"relative_path"));Path temporary=temporary(root,id);
        try {
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(temporary,LinkOption.NOFOLLOW_LINKS)) {
                if(!hash(temporary).equals(str(item,"content_hash")))throw new IOException();Files.move(temporary,path);
            }
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(root)||!hash(path).equals(str(item,"content_hash")))throw new IOException();
            Track track=track(str(item,"track_json"));BasicFileAttributes attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            transaction.executeWithoutResult(tx->{
                profiles.lockBindings();var source=sources.get(str(item,"source_id"));if(!source.location().equals(root.toString()))throw conflict("媒体库路径已改变，请检查原下载文件");var profile=profiles.savedProfile(source.parserProfileId());
                String relative=str(item,"relative_path"),songId=str(item,"song_id"),assetId=AuthCrypto.hash(str(item,"source_id")+":"+track.id()+":"+str(item,"resolved_quality"));
                mapper.insertSong(args("songId",songId,"sourceId",source.id(),"relativePath",relative,"pathHash",AuthCrypto.hash(relative),"size",attributes.size(),"modified",attributes.lastModifiedTime().toInstant().toString(),"now",now(),"profileId",profile.id(),"revision",profile.revision(),"result",encode(seed(track,path.getFileName().toString()))));
                mapper.deleteAsset(args("id",assetId));mapper.insertAsset(args("id",assetId,"sourceId",source.id(),"trackKey",track.id(),"quality",item.get("resolved_quality"),"relativePath",relative,"songId",songId,"size",attributes.size(),"modified",attributes.lastModifiedTime().toInstant().toString(),"hash",item.get("content_hash"),"trackJson",item.get("track_json"),"now",now()));
                finish(id,"SUCCESS",str(item,"resolved_quality"),relative,songId,str(item,"content_hash"),"");
            });
            String note=attachments(track,path,id);
            if(!note.isEmpty())finish(id,"SUCCESS",str(item,"resolved_quality"),str(item,"relative_path"),str(item,"song_id"),str(item,"content_hash"),note);
        }catch(IOException e){throw new LxNetwork.LxNetworkFailure("已发布文件不完整或不可读取，请检查挂载后重试");}
    }
    private String attachments(Track track,Path audio,String owner) {
        var warnings=new ArrayList<String>();String stem=audio.getFileName().toString().replaceFirst("\\.[^.]+$","");
        try{String lyrics=catalog.lyrics(track);if(lyrics.isBlank()||lyrics.getBytes(StandardCharsets.UTF_8).length>256*1024)warnings.add("未获取到歌词");else writeAttachment(audio.resolveSibling(stem+".lrc"),lyrics.getBytes(StandardCharsets.UTF_8));}catch(Exception e){warnings.add("歌词获取失败，可稍后补充");}
        try {
            if(track.coverUrl()==null||track.coverUrl().isBlank())warnings.add("未获取到封面");
            else {var response=network.request(track.coverUrl(),"GET",Map.of(),null,2_000_000,false,owner+":cover");byte[] bytes=response.bytes();String extension=imageExtension(bytes);if(response.status()!=200||extension==null)warnings.add("未获取到封面");else {LxCoverService.validate(bytes);writeAttachment(audio.resolveSibling(stem+"."+extension),bytes);}}
        }catch(Exception e){warnings.add("封面获取失败，可稍后补充");}
        return String.join("；",warnings);
    }
    private static void writeAttachment(Path path,byte[] bytes)throws IOException{if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;Path tmp=Files.createTempFile(path.getParent(),".singverse-attachment-",".tmp");try{Files.write(tmp,bytes);Files.move(tmp,path);}finally{Files.deleteIfExists(tmp);}}
    private static String imageExtension(byte[] b){if(b.length>=12){if((b[0]&255)==255&&(b[1]&255)==216)return "jpg";if((b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71)return "png";if(new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "webp";}return null;}
    private Path checkedRoot(Map<String,Object> item){var source=sources.get(str(item,"source_id"));if(!source.type().equals("LOCAL")||!source.location().equals(str(item,"source_path")))throw conflict("下载保存位置发生变化，请检查媒体库设置");return LxTargetService.writable(str(item,"source_path"),true);}
    static Path temporary(Path root,String id){try{Path area=root.resolve(".singverse-downloads");try{Files.createDirectory(area);}catch(FileAlreadyExistsException existing){}if(!Files.isDirectory(area,LinkOption.NOFOLLOW_LINKS)||!area.toRealPath().equals(area))throw new IOException();return area.resolve(id+".part");}catch(IOException e){throw conflict("下载临时目录不可写，请检查 NAS 挂载与目录权限");}}
    private static Path safeFile(Path root,String relative){Path path=root.resolve(relative).normalize();if(relative.isBlank()||Path.of(relative).isAbsolute()||!path.getParent().equals(root)||!path.startsWith(root)||Files.isSymbolicLink(path))throw bad("歌曲文件路径无效");return path;}
    private static String filename(Path root,Track track,String quality,String extension){String base=safeName(String.join("、",track.artists())+" - "+track.title());String result=base+"."+extension;if(!Files.exists(root.resolve(result),LinkOption.NOFOLLOW_LINKS))return result;String suffix=" ["+track.platform()+"-"+track.id().substring(0,8)+"-"+quality+"]";result=base+suffix+"."+extension;for(int i=2;Files.exists(root.resolve(result),LinkOption.NOFOLLOW_LINKS);i++)result=base+suffix+" ("+i+")."+extension;return result;}
    private static String safeName(String value){String cleaned=value.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]","_").replaceAll("[. ]+$","").trim();while(cleaned.getBytes(StandardCharsets.UTF_8).length>160)cleaned=cleaned.substring(0,cleaned.offsetByCodePoints(cleaned.length(),-1));return cleaned.isBlank()?"歌曲":cleaned;}
    static String songId(String source,String relative){return UUID.nameUUIDFromBytes((source+"\0"+relative).getBytes(StandardCharsets.UTF_8)).toString();}
    public static FilenameParseResult seed(Track track,String filename){String extension=filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";return new FilenameParseResult(filename,filename,track.title(),track.artists(),FilenameParseResult.Language.UNKNOWN,FilenameParseResult.VersionType.UNKNOWN,null,extension,List.of(),1,FilenameParseResult.ParseStatus.SUCCESS,FilenameParseResult.RuleId.NONE,List.of(),false,List.of());}
    private void fail(String id,String message){transaction.executeWithoutResult(tx->{mapper.lockSettings();var item=mapper.item(args("id",id));if(item==null||Set.of("SUCCESS","SKIPPED","CANCELLED").contains(str(item,"status")))return;finish(id,"FAILED",str(item,"resolved_quality"),str(item,"relative_path"),str(item,"song_id"),str(item,"content_hash"),clean(message));settle(str(item,"batch_id"));});}
    private void handleFailure(String id,Exception error) {
        var item=mapper.item(args("id",id));if(item==null)return;
        if("PUBLISHING".equals(str(item,"status"))&&(closing||error instanceof org.springframework.dao.DataAccessException))return;
        fail(id,explanation(error));
        try {Path root=checkedRoot(item);Files.deleteIfExists(temporary(root,id));}catch(Exception ignored){}
    }
    private boolean cancelled(String id){var item=mapper.item(args("id",id));return closing||item==null||str(item,"status").equals("CANCELLED");}
    private void finish(String id,String status,String quality,String path,String song,String hash,String message){var old=mapper.item(args("id",id));if(old==null||str(old,"status").equals("CANCELLED")&&!status.equals("CANCELLED"))return;boolean active=Set.of("PUBLISHING","RESOLVING","DOWNLOADING","QUEUED").contains(status);mapper.updateItem(args("id",id,"status",status,"resolvedQuality",quality,"relativePath",path,"songId",song,"hash",hash,"message",clean(message),"now",now(),"activeKey",active?old.get("active_key"):null,"resolvedActiveKey",active?old.get("resolved_active_key"):null));}
    private void settle(String id){var row=mapper.batch(args("id",id));if(row==null)return;long queued=0,active=0,success=0,failed=0,interrupted=0,cancelled=0;for(var c:mapper.counts(args("id",id))){long n=number(c,"amount");switch(str(c,"status")){case "QUEUED"->queued+=n;case "RESOLVING","DOWNLOADING","PUBLISHING"->active+=n;case "SUCCESS","SKIPPED"->success+=n;case "FAILED"->failed+=n;case "INTERRUPTED"->interrupted+=n;case "CANCELLED"->cancelled+=n;default->{}}}String state=active>0?str(row,"status").equals("CANCELLING")?"CANCELLING":"RUNNING":queued>0?"QUEUED":success>0?(failed+interrupted+cancelled>0?"PARTIAL":"COMPLETED"):failed>0?"FAILED":interrupted>0?"INTERRUPTED":"CANCELLED";String now=now();mapper.updateBatch(args("id",id,"status",state,"now",now,"started",active>0?now:null,"finished",active+queued==0?now:null));}
    private static boolean qualityUnavailable(String message){return message!=null&&message.toLowerCase(Locale.ROOT).matches("(?s).*(音质|quality|not.?found|不支持|不存在|无损|未找到|没有找到).* ".trim());}
    private static Map<String,Object> withPath(Map<String,Object> asset,Path root){var map=new HashMap<>(asset);map.put("source_path",root.toString());return map;}
    static String hash(Path path)throws IOException{try{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private Track track(String value){try{return json.readValue(value,Track.class);}catch(Exception e){throw bad("歌曲预览信息失效，请重新搜索");}}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw bad("保存的数据无法读取，请重新导入");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("下载数据编码失败");}}
    static String utf8(byte[] bytes){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw bad("音源文件需采用 UTF-8 编码");}}
    private static String header(String script,String key,String fallback){Matcher m=Pattern.compile("(?m)^\\s*\\*?\\s*@"+key+"\\s+([^\\r\\n]+)").matcher(script);String value=m.find()?m.group(1).replace("*/","").trim():fallback;return value.substring(0,Math.min(100,value.length()));}
    static String explanation(Exception error){if(error instanceof org.springframework.dao.DataAccessException)return "暂时无法保存任务，请稍后重试";return clean(error instanceof org.springframework.web.server.ResponseStatusException e?e.getReason():error.getMessage()==null?"操作失败，请重试":error.getMessage());}
    private static String now(){return Instant.now().toString();}
    private static void bounds(int page,int size){if(page<0||page>100000||size<1||size>100)throw bad("分页参数无效");}
    @PreDestroy public void close(){closing=true;running.forEach(network::cancel);downloads.shutdownNow();catalogReads.shutdownNow();}
}
