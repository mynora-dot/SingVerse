package com.mynora.singverse.media.webdav.metadata;
import static com.mynora.singverse.media.webdav.metadata.WebDavAccess.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Durable snapshot and cursor; files are bounded independently of library size. */
@Service
public class WebDavShareJobs {
    private final com.mynora.singverse.media.metadata.MetadataFileService fileService;
    private final com.mynora.singverse.media.metadata.MetadataMapper applied;
    private final WebDavShareJobMapper mapper;
    private final MediaSourceRepository sources;
    private final WebDavMetadataService metadata;
    private final WebDavShareService sharing;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Path root;
    private final boolean enabled;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"share-export");t.setDaemon(true);return t;});
    private final AtomicBoolean running=new AtomicBoolean();
    public WebDavShareJobs(com.mynora.singverse.media.metadata.MetadataMapper applied,com.mynora.singverse.media.metadata.MetadataFileService fileService,WebDavShareJobMapper mapper,MediaSourceRepository sources,WebDavMetadataService metadata,WebDavShareService sharing,ObjectMapper json,PlatformTransactionManager manager,@Value("${singverse.webdav.metadata-path:./data/metadata/webdav}")String path,@Value("${singverse.webdav.share-worker-enabled:true}")boolean enabled){this.applied=applied;this.fileService=fileService;this.enabled=enabled;this.sharing=sharing;this.mapper=mapper;this.sources=sources;this.metadata=metadata;this.json=json;this.tx=new TransactionTemplate(manager);root=Path.of(path).toAbsolutePath().normalize().resolve("shares");}
    public Map<String,Object> create(String sourceId,String baseId){
        return tx.execute(t->{applied.lock();mapper.lock(Map.of());var source=sources.get(sourceId);
            if(baseId!=null&&!baseId.isBlank()){var base=get(sourceId,baseId);if(!text(base,"binding").equals(binding(source))||!text(base,"state").equals("SUCCEEDED"))throw failure(409,"Base snapshot is not compatible");}
            if(mapper.active(args("sourceId",sourceId)).size()>0)throw failure(409,"An export is already active");
            if(mapper.active(args("sourceId",null)).size()>=8)throw failure(409,"Export queue is full");
            String id=UUID.randomUUID().toString();var a=args("id",id,"sourceId",sourceId,"binding",binding(source),"baseId",baseId==null||baseId.isBlank()?null:baseId,"now",Instant.now().toString());
            mapper.insert(a);
            if(fileService.pending(sourceId)>0){fileService.retry(sourceId);mapper.sourceCount(a);progress(a,"WAITING","",0,0,null);}
            else {mapper.snapshot(a);mapper.count(a);}
            return get(sourceId,id);
        });
    }
    public Map<String,Object> get(String sourceId,String id){sources.get(sourceId);var rows=mapper.find(args("sourceId",sourceId,"id",id));if(rows.isEmpty())throw failure(404,"Share job not found");var result=new HashMap<String,Object>(rows.getFirst());try(var files=Files.list(folder(id))){result.put("deletion_count",files.filter(f->f.getFileName().toString().startsWith("deletions-")).count());}catch(java.io.IOException ignored){result.put("deletion_count",0);}return result;}
    public Map<String,Object> latest(String sourceId){sources.get(sourceId);var rows=mapper.latest(args("sourceId",sourceId));return rows.isEmpty()?Map.of():get(sourceId,text(rows.getFirst(),"id"));}
    public void resume(String sourceId,String id){tx.executeWithoutResult(t->{mapper.lock(Map.of());var row=get(sourceId,id);if(text(row,"state").equals("SUCCEEDED"))return;
        boolean waiting=number(row,"part_count")==0&&mapper.snapshotCount(args("id",id))==0;
        if(waiting)fileService.retry(sourceId);
        progress(row,waiting?"WAITING":"QUEUED",text(row,"cursor_id"),number(row,"processed_count"),(int)number(row,"part_count"),null);
    });}
    public Path part(String sourceId,String id,int part){var row=get(sourceId,id);if(part<1||part>number(row,"part_count"))throw failure(404,"Part not ready");return folder(id).resolve(String.format(Locale.ROOT,"part-%06d.zip",part));}
    public ObjectNode index(String sourceId,String id){var job=get(sourceId,id);var out=json.createObjectNode();out.put("format","singverse-share-set");out.put("version",2);out.put("id",id);out.put("state",text(job,"state"));out.put("total",number(job,"total_count"));out.put("baseId",text(job,"base_id"));var deletions=out.putArray("deletions");try(var files=Files.list(folder(id))){files.map(f->f.getFileName().toString()).filter(n->n.startsWith("deletions-")).sorted().forEach(deletions::add);}catch(java.io.IOException ignored){}var parts=out.putArray("parts");for(int i=1;i<=number(job,"part_count");i++)parts.add(String.format(Locale.ROOT,"part-%06d.zip",i));return out;}
    private void writeDeletions(Map<String,Object> job)throws java.io.IOException {
        String base=text(job,"base_id");if(base.isEmpty())return;String cursor="";int part=0;
        while(true){var rows=mapper.deleted(args("baseId",base,"id",job.get("id"),"cursor",cursor));if(rows.isEmpty())break;var data=json.createObjectNode();data.put("advisoryOnly",true);var paths=data.putArray("paths");for(var row:rows){paths.add(text(row,"relative_path"));cursor=text(row,"song_id");}Files.write(folder(text(job,"id")).resolve(String.format(Locale.ROOT,"deletions-%06d.json",++part)),json.writeValueAsBytes(data));}
    }
    public Path deletions(String sourceId,String id,int part){get(sourceId,id);if(part<1||part>100000)throw failure(404,"Invalid deletion part");Path path=folder(id).resolve(String.format(Locale.ROOT,"deletions-%06d.json",part));if(!Files.isRegularFile(path))throw failure(404,"No deletion advisory");return path;}
    public Map<String,Integer> importPart(String sourceId,byte[] bytes)throws java.io.IOException {
        if(bytes.length>WebDavShareService.LIMIT)throw failure(413,"Package exceeds 32 MiB");
        var source=sources.get(sourceId);String binding=binding(source);String hash=com.mynora.singverse.auth.crypto.AuthCrypto.hash(Base64.getEncoder().encodeToString(bytes));
        var key=args("sourceId",sourceId,"binding",binding,"hash",hash);var doc=sharing.readManifest(bytes);var entries=doc.path("entries");
        var assetMap=doc.path("assets");if(assetMap.isObject())for(var asset:((ObjectNode)assetMap).properties()){
            String encoded=asset.getValue().asText();if(!asset.getKey().equals(com.mynora.singverse.auth.crypto.AuthCrypto.hash(encoded)))throw failure(400,"Asset hash mismatch");
            metadata.installSharedCover(encoded);
        }
        tx.executeWithoutResult(t->{mapper.lock(Map.of());mapper.receiptInsert(key);});var receipt=mapper.receipt(key).getFirst();int cursor=(int)number(receipt,"processed"),imported=(int)number(receipt,"imported"),skipped=(int)number(receipt,"skipped");
        while(cursor<entries.size()){
            var pack=json.createObjectNode();pack.put("format","singverse-media-index");pack.put("version",2);var batch=pack.putArray("entries");long used=0;
            for(int i=cursor;i<entries.size()&&batch.size()<100;i++){
                var entry=(ObjectNode)entries.get(i).deepCopy();var snapshot=(ObjectNode)entry.path("metadata");String ref=snapshot.path("coverRef").asText("");if(!ref.isEmpty())snapshot.put("coverData",Base64.getEncoder().encodeToString(metadata.sharedCover(ref)));
                long size=json.writeValueAsBytes(entry).length;if(used+size>4*1024*1024&&batch.size()>0)break;batch.add(entry);used+=size;
            }
            byte[] small=sharing.pack(pack);final int start=cursor,next=cursor+batch.size(),priorImported=imported,priorSkipped=skipped;
            var result=tx.execute(t->{
                mapper.receiptLock(key);if(number(mapper.receipt(key).getFirst(),"processed")!=start)return null;
                if(!binding(sources.get(sourceId)).equals(binding))throw failure(409,"Source root changed during import");
                try{var values=sharing.importBundle(sourceId,small);mapper.receiptUpdate(args("sourceId",sourceId,"binding",binding,"hash",hash,"processed",next,"imported",priorImported+values.get("imported"),"skipped",priorSkipped+values.get("skipped")));return values;}catch(java.io.IOException error){throw failure(400,"Invalid package");}
            });
            if(result==null){var current=mapper.receipt(key).getFirst();cursor=(int)number(current,"processed");imported=(int)number(current,"imported");skipped=(int)number(current,"skipped");continue;}
            imported+=result.get("imported");skipped+=result.get("skipped");cursor=next;
        }
        return Map.of("imported",imported,"skipped",skipped,"processed",cursor);
    }
    public static final long UPLOAD_LIMIT=8L*1024*1024*1024;
    /** Consume an outer set one bounded part at a time; receipts survive an interrupted upload. */
    public Map<String,Integer> importStream(String sourceId,java.io.InputStream input)throws java.io.IOException {
        if(!sources.get(sourceId).type().equals("WEBDAV"))throw failure(400,"请选择 WebDAV 媒体库导入");
        var buffered=new java.io.BufferedInputStream(new UploadInput(input));buffered.mark(131072);
        boolean set;
        try(var probe=new java.util.zip.ZipInputStream(new java.io.FilterInputStream(buffered){@Override public void close(){}},java.nio.charset.StandardCharsets.UTF_8)){
            var first=probe.getNextEntry();set=first!=null&&first.getName().equals("index.json");
        }catch(java.util.zip.ZipException error){throw failure(400,"分享包格式无效");}
        buffered.reset();
        if(!set)return importPart(sourceId,buffered.readNBytes(WebDavShareService.LIMIT+1));
        try(var zip=new java.util.zip.ZipInputStream(buffered,java.nio.charset.StandardCharsets.UTF_8)){
            zip.getNextEntry();byte[] indexBytes=zip.readNBytes(1024*1024+1);
            if(indexBytes.length>1024*1024)throw failure(413,"分享清单过大");
            var index=json.readTree(indexBytes);
            if(index==null||!index.path("format").asText().equals("singverse-share-set")||index.path("version").asInt()!=2||!index.path("parts").isArray()||index.path("parts").size()>4096)throw failure(400,"分享清单无效");
            var expected=new ArrayList<String>();
            for(var part:index.path("parts")){String name=part.asText();if(!name.matches("part-[0-9]{6}\\.zip")||expected.contains(name))throw failure(400,"分享分包清单无效");expected.add(name);}
            Set<String> advisories=new HashSet<>();
            if(index.path("deletions").size()>4096)throw failure(400,"删除提示过多");
            for(var deletion:index.path("deletions")){String name=deletion.asText();if(!name.matches("deletions-[0-9]{6}\\.json")||!advisories.add(name))throw failure(400,"删除提示无效");}
            int cursor=0,imported=0,skipped=0,processed=0;long expanded=indexBytes.length;Set<String> seen=new HashSet<>();
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();if(!seen.add(name))throw failure(400,"分享包中有重复文件");
                boolean part=cursor<expected.size()&&expected.get(cursor).equals(name);
                if(!part&&!advisories.contains(name))throw failure(400,"分享包分包缺失、顺序错误或包含未知文件");
                byte[] bytes=zip.readNBytes(WebDavShareService.LIMIT+1);expanded+=bytes.length;
                if(bytes.length>WebDavShareService.LIMIT||expanded>UPLOAD_LIMIT)throw failure(413,"分享包过大");
                if(part){var result=importPart(sourceId,bytes);imported+=result.get("imported");skipped+=result.get("skipped");processed+=result.get("processed");cursor++;}
            }
            if(cursor!=expected.size())throw failure(400,"分享包不完整，请重新选择完整文件");
            return Map.of("imported",imported,"skipped",skipped,"processed",processed);
        }catch(java.util.zip.ZipException|com.fasterxml.jackson.core.JsonProcessingException error){throw failure(400,"分享包格式无效");}
    }
    private static final class UploadInput extends java.io.FilterInputStream {
        private long remaining=UPLOAD_LIMIT;
        UploadInput(java.io.InputStream input){super(input);}
        @Override public int read()throws java.io.IOException {int value=in.read();if(value>=0&&--remaining<0)throw failure(413,"分享包超过 8 GiB");return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException {int count=in.read(bytes,offset,(int)Math.min(length,remaining+1));if(count>0&&(remaining-=count)<0)throw failure(413,"分享包超过 8 GiB");return count;}
    }
    public void download(String sourceId,String id,java.io.OutputStream output)throws java.io.IOException {
        var job=get(sourceId,id);if(!text(job,"state").equals("SUCCEEDED"))throw failure(409,"Share package is still being generated");
        try(var zip=new java.util.zip.ZipOutputStream(output,java.nio.charset.StandardCharsets.UTF_8)){
            zip.setLevel(0);zip.putNextEntry(new java.util.zip.ZipEntry("index.json"));zip.write(json.writeValueAsBytes(index(sourceId,id)));zip.closeEntry();
            for(int part=1;part<=number(job,"part_count");part++){Path path=part(sourceId,id,part);zip.putNextEntry(new java.util.zip.ZipEntry(path.getFileName().toString()));Files.copy(path,zip);zip.closeEntry();}
            for(int part=1;part<=number(job,"deletion_count");part++){Path path=deletions(sourceId,id,part);zip.putNextEntry(new java.util.zip.ZipEntry(path.getFileName().toString()));Files.copy(path,zip);zip.closeEntry();}
        }
    }
    private Path folder(String id){if(!id.matches("[a-f0-9-]{36}"))throw failure(400,"Invalid job ID");return root.resolve(id);}
    private void progress(Map<String,Object> job,String state,String cursor,long processed,int parts,String error){mapper.progress(args("id",job.get("id"),"state",state,"cursor",cursor,"processed",processed,"parts",parts,"error",error));}
    @Scheduled(fixedDelay=2000,initialDelay=5000)public void tick(){if(!enabled||!running.compareAndSet(false,true))return;executor.execute(()->{try{runNext();}finally{running.set(false);}});}
    public void runNext(){var pending=mapper.pending(Map.of());Map<String,Object> selected=null;
        for(var candidate:pending){
            if(text(candidate,"state").equals("WAITING")){
                String sourceId=text(candidate,"source_id");
                if(((Number)fileService.status(sourceId).get("failed")).intValue()>0){progress(candidate,"FAILED","",0,0,"Share preparation is temporarily unavailable");continue;}
                if(fileService.pending(sourceId)>0)continue;
                var waitingJob=candidate;
                boolean ready=Boolean.TRUE.equals(tx.execute(t->{applied.lock();mapper.lock(Map.of());
                    var current=get(sourceId,text(waitingJob,"id"));if(!text(current,"state").equals("WAITING"))return List.of("QUEUED","RUNNING").contains(text(current,"state"));
                    if(!binding(sources.get(sourceId)).equals(text(waitingJob,"binding"))){progress(waitingJob,"FAILED","",0,0,"Source root changed");return false;}
                    if(fileService.pending(sourceId)>0)return false;
                    var a=args("id",waitingJob.get("id"),"sourceId",sourceId);mapper.snapshot(a);mapper.count(a);progress(waitingJob,"QUEUED","",0,0,null);return true;
                }));
                if(!ready)continue;
                candidate=get(sourceId,text(candidate,"id"));
            }
            selected=candidate;break;
        }
        if(selected==null)return;var job=selected;String id=text(job,"id"),cursor=text(job,"cursor_id");long processed=number(job,"processed_count");int part=(int)number(job,"part_count");
        try{
            Files.createDirectories(folder(id));boolean done=false;
            while(!done&&!Thread.currentThread().isInterrupted()){
                var manifest=json.createObjectNode();manifest.put("format","singverse-media-index");manifest.put("version",2);manifest.put("setId",id);manifest.put("part",part+1);manifest.put("baseId",text(job,"base_id"));var entries=manifest.putArray("entries");var assets=manifest.putObject("assets");long bytes=0;int visited=0;
                while(entries.size()<5000&&visited<5000){var page=mapper.page(args("id",id,"cursor",cursor,"baseId",job.get("base_id")));if(page.isEmpty()){done=true;processed=number(job,"total_count");break;}boolean full=false;
                    for(var row:page){String song=text(row,"song_id");
                        var item=json.createObjectNode();String path=text(row,"relative_path");WebDavShareService.validatePath(path);item.put("path",path);if(row.get("file_size") instanceof Number n)item.put("size",n.longValue());
                        JsonNode result=row.get("result_json")==null?json.createObjectNode():json.readTree(text(row,"result_json"));var data=item.putObject("metadata");
                        for(String key:List.of("tags","technical","lyrics","fields","extractorVersion","evidence","origins","aliases"))if(result.has(key))data.set(key,result.get(key));
                        if(!data.has("tags")){var parsed=json.readTree(text(row,"filename_json"));var tags=data.putObject("tags");tags.put("title",parsed.path("title").asText(""));tags.set("artists",parsed.path("artists").isArray()?parsed.path("artists"):json.createArrayNode());data.put("evidence","FILENAME");}
                        String hash=result.path("cover").asText("");String asset=null;
                        if(hash.matches("[a-f0-9]{64}")){data.put("coverRef",hash);if(!assets.has(hash)&&mapper.asset(args("id",id,"hash",hash)).isEmpty())asset=Base64.getEncoder().encodeToString(metadata.sharedCover(hash));}
                        long size=6L*json.writeValueAsBytes(item).length+12L*path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+(asset==null?0:2L*asset.length());if(bytes+size>24*1024*1024&&entries.size()>0){full=true;break;}
                        entries.add(item);if(asset!=null)assets.put(hash,asset);bytes+=size;cursor=song;processed++;visited++;if(entries.size()==5000||visited==5000){full=true;break;}
                    }
                    if(full)break;
                }
                if(entries.size()>0){if(Files.getFileStore(root).getUsableSpace()<64L*1024*1024)throw failure(507,"Not enough disk space");byte[] pack=sharing.pack(manifest);Path target=folder(id).resolve(String.format(Locale.ROOT,"part-%06d.zip",part+1));Path temp=Files.createTempFile(folder(id),"part-",".tmp");try{Files.write(temp,pack);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}part++;}
                if(done)writeDeletions(job);
                final String checkpoint=cursor;final long count=processed;final int parts=part;final boolean finished=done;
                tx.executeWithoutResult(t->{assets.fieldNames().forEachRemaining(hash->mapper.assetInsert(args("id",id,"hash",hash)));progress(job,finished?"SUCCEEDED":"RUNNING",checkpoint,count,parts,null);});
            }
        }catch(Exception error){var saved=mapper.find(args("sourceId",job.get("source_id"),"id",id));if(!saved.isEmpty()){var row=saved.getFirst();progress(job,"FAILED",text(row,"cursor_id"),number(row,"processed_count"),(int)number(row,"part_count"),"Export interrupted; resume to retry");}}
    }
    @Scheduled(fixedDelay=3600000,initialDelay=60000) public void cleanup() {
        var cutoff=args("before",Instant.now().minusSeconds(7L*86400).toString());
        for(var row:mapper.expired(cutoff)){
            String id=text(row,"id");
            try{tx.executeWithoutResult(t->{
                mapper.lock(Map.of());
                if(mapper.expired(cutoff).stream().noneMatch(r->id.equals(text(r,"id"))))return;
                try{Path folder=folder(id);if(Files.isDirectory(folder))try(var files=Files.walk(folder)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
                catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
                mapper.deleteEntries(args("id",id));mapper.deleteAssets(args("id",id));mapper.deleteJob(args("id",id));
            });}catch(java.io.UncheckedIOException ignored){}
        }
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
