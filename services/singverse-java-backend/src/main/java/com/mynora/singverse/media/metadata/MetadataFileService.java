package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.webdav.metadata.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable per-song file projection. Never writes to the media source. */
@Service
public class MetadataFileService {
    private final MetadataFileMapper files;
    private final MediaSongRepository songs;
    private final MediaSourceRepository sources;
    private final WebDavMetadataMapper remote;
    private final WebDavMetadataService metadata;
    private final MetadataCoverService covers;
    private final WebDavShareService sharing;
    private final ObjectMapper json;
    private final Path root;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"metadata-files");thread.setDaemon(true);return thread;});
    public MetadataFileService(MetadataFileMapper files, MediaSongRepository songs, MediaSourceRepository sources,
            WebDavMetadataMapper remote, WebDavMetadataService metadata, MetadataCoverService covers,
            WebDavShareService sharing, ObjectMapper json,
            @Value("${singverse.webdav.metadata-path:./data/metadata/webdav}") String path,
            @Value("${singverse.metadata.file-worker-enabled:true}") boolean enabled) {
        this.files=files; this.songs=songs; this.sources=sources; this.remote=remote; this.metadata=metadata;
        this.covers=covers; this.sharing=sharing; this.json=json; this.root=Path.of(path).toAbsolutePath().normalize().resolve("library"); this.enabled=enabled;
    }
    public Path directory(String sourceId, String songId) { return root.resolve(UUID.fromString(sourceId).toString()).resolve(UUID.fromString(songId).toString()); }
    public int pending(String sourceId) { return files.pendingSource(Map.of("sourceId",sourceId)); }
    public void retry(String sourceId) { files.retrySource(Map.of("sourceId",sourceId)); }
    public Map<String,Object> status(String sourceId) {
        return Map.of("pending",pending(sourceId),"failed",files.failures(Map.of("sourceId",sourceId)),"attachments",files.attachments(Map.of("sourceId",sourceId)),"directory","library/"+sourceId);
    }
    public Map<String,Object> localSummary(String sourceId) {
        sources.get(sourceId);
        return new HashMap<>(Map.of("enabled",false,"paused",false,"accessGroup","","privateOrigins",List.of(),"counts",Map.of("COMPLETE",songs.count(sourceId)),"presence",Map.of(),"budget",Map.of("files_started",0,"bytes_read",0),"emptyScans",0,"canConfirmEmpty",false));
    }
    @Scheduled(fixedDelay=2000,initialDelay=5000)
    public void tick() {
        if(enabled && scheduled.compareAndSet(false,true))executor.submit(()->{try{runNext();}finally{scheduled.set(false);}});
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
    public void runNext() {
        if(!running.compareAndSet(false,true))return;
        try { for(var row:files.pending(Map.of("retryBefore",java.time.Instant.now().minusSeconds(300).toString()))) {
            var args=new HashMap<String,Object>(); args.put("songId",row.get("song_id")); args.put("revision",row.get("revision")); args.put("now",java.time.Instant.now().toString());
            try { write(row,args); } catch(Exception error) { files.failed(args); }
        } } finally { running.set(false); }
    }
    private void write(Map<String,Object> row, Map<String,Object> args) throws Exception {
        String songId=row.get("song_id").toString(); var song=songs.get(songId); var source=sources.get(song.sourceId());
        args.put("error",null);
        ObjectNode snapshot=json.createObjectNode();
        var cached=remote.resource(Map.of("songId",songId));
        if(!cached.isEmpty() && cached.getFirst().get("result_json")!=null) {
            var prior=json.readTree(cached.getFirst().get("result_json").toString());
            for(String field:List.of("tags","technical","lyrics","fields","extractorVersion","evidence","aliases"))if(prior.has(field))snapshot.set(field,prior.get(field));
        }
        var tags=snapshot.path("tags").isObject()?(ObjectNode)snapshot.get("tags"):snapshot.putObject("tags");
        if(!tags.hasNonNull("title"))tags.put("title",song.result().title());
        if(!tags.path("artists").isArray()||tags.path("artists").isEmpty())tags.set("artists",json.valueToTree(song.result().artists()));
        if(!snapshot.has("evidence"))snapshot.put("evidence",appliedEvidence(cached));
        var applied=song.metadata();
        if(applied!=null && !applied.stale()) {
            var track=applied.track(); var fields=applied.fields();
            if(fields.contains("title"))tags.put("title",track.title());
            if(fields.contains("artists"))tags.set("artists",json.valueToTree(track.artists()));
            if(fields.contains("album"))tags.put("album",track.album());
            if(fields.contains("releaseDate"))tags.put("releaseDate",track.releaseDate());
            if(fields.contains("aliases"))snapshot.set("aliases",json.valueToTree(track.aliases()));
            if(fields.contains("durationMs")&&track.durationMs()!=null) {
                var technical=snapshot.path("technical").isObject()?(ObjectNode)snapshot.get("technical"):snapshot.putObject("technical");
                technical.put("duration",track.durationMs()/1000.0);
            }
            snapshot.put("evidence","APPLIED"); snapshot.set("origins",json.valueToTree(applied.origins()));
            var coverage=snapshot.path("fields").isObject()?(ObjectNode)snapshot.get("fields"):snapshot.putObject("fields");
            for(String field:fields)if(Set.of("title","artists","album","releaseDate").contains(field))coverage.put(field,"PRESENT");
            if(fields.contains("cover")&&track.coverUrl()!=null) {
                String key=com.mynora.singverse.auth.crypto.AuthCrypto.hash(track.coverUrl());snapshot.put("coverKey",key);
                try {
                    var cover=covers.applied(songId);
                    String hash=metadata.installSharedCover(Base64.getEncoder().encodeToString(portableCover(cover.bytes())));
                    snapshot.put("cover",hash);coverage.put("cover","PRESENT");
                } catch(Exception unavailable) {
                    // Optional artwork must not prevent current text metadata from being saved/shared.
                    args.put("error","封面待补齐");coverage.put("cover","UNKNOWN");
                    var previous=files.previous(Map.of("songId",songId));
                    if(!previous.isEmpty()&&previous.getFirst().get("result_json")!=null){
                        var prior=json.readTree(previous.getFirst().get("result_json").toString());
                        if(prior.path("coverKey").asText().equals(key)&&prior.path("cover").asText().matches("[a-f0-9]{64}"))snapshot.set("cover",prior.get("cover"));
                    }
                }
            }
        }
        ObjectNode manifest=json.createObjectNode(); manifest.put("format","singverse-media-index"); manifest.put("version",2);
        var item=manifest.putArray("entries").addObject(); item.put("path","media"); item.set("metadata",snapshot.deepCopy());
        if(snapshot.has("cover")) ((ObjectNode)item.get("metadata")).put("coverData",Base64.getEncoder().encodeToString(metadata.sharedCover(snapshot.get("cover").asText())));
        Path directory=directory(song.sourceId(),song.id()); Files.createDirectories(directory);
        Set<String> expected=new HashSet<>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(sharing.pack(manifest)))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) { String name=entry.getName(); expected.add(name); atomic(directory.resolve(name),zip.readAllBytes()); }
        }
        // Managed STRM is playable locally; exported STRM always uses a replaceable origin.
        String target=source.type().equals("LOCAL")?Path.of(source.location()).resolve(song.relativePath()).toString():new java.net.URI("https",null,"replace.invalid",-1,"/dav/"+song.relativePath(),null,null).toASCIIString();
        atomic(directory.resolve("media.strm"),(target+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var record=json.createObjectNode(); record.put("relativePath",song.relativePath()); record.put("sourceType",source.type()); record.set("metadata",snapshot);
        atomic(directory.resolve("metadata.json"),json.writeValueAsBytes(record)); expected.add("metadata.json");
        try(var children=Files.list(directory)) { for(Path child:children.toList())if(!expected.contains(child.getFileName().toString()))Files.deleteIfExists(child); }
        args.put("location",source.location()); args.put("result",json.writeValueAsString(snapshot)); files.complete(args);
    }
    private static byte[] portableCover(byte[] bytes)throws Exception {
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if(image==null)throw new java.io.IOException("Invalid artwork");
        while(true){
            var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);
            if(output.size()<=2_000_000)return output.toByteArray();
            if(Math.max(image.getWidth(),image.getHeight())<=128)throw new java.io.IOException("Artwork exceeds limit");
            var smaller=new java.awt.image.BufferedImage(Math.max(1,image.getWidth()*3/4),Math.max(1,image.getHeight()*3/4),java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics=smaller.createGraphics();try{graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(image,0,0,smaller.getWidth(),smaller.getHeight(),null);}finally{graphics.dispose();}
            image=smaller;
        }
    }
    private String appliedEvidence(List<Map<String,Object>> cached)throws Exception {
        if(cached.isEmpty()||cached.getFirst().get("result_json")==null)return "FILENAME";
        var tags=json.readTree(cached.getFirst().get("result_json").toString()).path("tags");
        return tags.isEmpty()?"FILENAME":"FILE";
    }
    private static void atomic(Path target,byte[] bytes)throws Exception {
        Path temp=Files.createTempFile(target.getParent(),"metadata-",".tmp");
        try { Files.write(temp,bytes); Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temp); }
    }
}
