package com.mynora.singverse.media.webdav.metadata;

import static com.mynora.singverse.media.webdav.metadata.WebDavAccess.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.service.FilenameParserConfigService;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Portable snapshots contain relative identities, never source credentials or signed URLs. */
@Service
public class WebDavShareService {
    public static final int LIMIT=32*1024*1024;
    private final WebDavMetadataMapper mapper;
    private final WebDavMetadataService metadata;
    private final MediaSourceRepository sources;
    private final MediaSongMapper songs;
    private final FilenameParserConfigService profiles;
    private final ObjectMapper json;
    public WebDavShareService(WebDavMetadataMapper mapper,WebDavMetadataService metadata,MediaSourceRepository sources,MediaSongMapper songs,FilenameParserConfigService profiles,ObjectMapper json){this.mapper=mapper;this.metadata=metadata;this.sources=sources;this.songs=songs;this.profiles=profiles;this.json=json;}
    public byte[] pack(ObjectNode manifest)throws IOException {
        var entries=manifest.path("entries");
        var bytes=new ByteArrayOutputStream();
        try(var zip=new LimitedZip(bytes)){
            write(zip,"manifest.json",json.writeValueAsBytes(manifest));
            if(manifest.path("assets").isObject())for(var asset:((ObjectNode)manifest.path("assets")).properties()){write(zip,"assets/"+asset.getKey()+".png",Base64.getDecoder().decode(asset.getValue().asText()));}
            for(var item:entries){String path=item.path("path").asText();var snapshot=item.path("metadata");
                // Deliberately use a replaceable origin rather than the owner's private address.
                String url;try{url=new java.net.URI("https",null,"replace.invalid",-1,"/dav/"+path,null,null).toASCIIString();}catch(Exception error){throw failure(400,"文件路径无效");}
                write(zip,path+".strm",(url+"\n").getBytes(StandardCharsets.UTF_8));
                var tags=snapshot.path("tags");String nfo="<?xml version=\"1.0\" encoding=\"UTF-8\"?><musicvideo><title>"+xml(tags.path("title").asText(""))+"</title><album>"+xml(tags.path("album").asText(""))+"</album>";
                for(var artist:tags.path("artists"))nfo+="<artist>"+xml(artist.asText())+"</artist>";
                if(tags.hasNonNull("releaseDate"))nfo+="<premiered>"+xml(tags.path("releaseDate").asText())+"</premiered>";
                if(snapshot.path("technical").path("duration").asDouble()>0)nfo+="<runtime>"+(long)Math.ceil(snapshot.path("technical").path("duration").asDouble()/60)+"</runtime>";
                if(snapshot.has("coverData"))nfo+="<thumb>"+xml(path.substring(path.lastIndexOf('/')+1)+".png")+"</thumb>";
                if(snapshot.path("coverRef").asText("").matches("[a-f0-9]{64}"))nfo+="<thumb>"+"../".repeat((int)path.chars().filter(c->c==47).count())+"assets/"+snapshot.path("coverRef").asText()+".png</thumb>";
                write(zip,path+".nfo",(nfo+"</musicvideo>").getBytes(StandardCharsets.UTF_8));
                if(snapshot.has("coverData"))write(zip,path+".png",Base64.getDecoder().decode(snapshot.path("coverData").asText()));
                var lyrics=snapshot.path("lyrics");if(lyrics.isObject()){
                    write(zip,path+".lyrics.json",json.writeValueAsBytes(lyrics));
                    if(lyrics.path("timing").asText().equals("milliseconds")&&lyrics.path("lines").isArray()){
                        var lrc=new StringBuilder();
                        for(var line:lyrics.path("lines")){long time=Math.max(0,line.path("time").asLong());lrc.append(String.format(Locale.ROOT,"[%02d:%02d.%02d]%s%n",time/60000,time/1000%60,time/10%100,line.path("text").asText()));}
                        write(zip,path+".lrc",lrc.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    if(Set.of("none","lrc").contains(lyrics.path("timing").asText()))write(zip,path+(lyrics.path("timing").asText().equals("lrc")?".lrc":".txt"),lyrics.path("text").asText().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        if(bytes.size()>LIMIT)throw failure(400,"索引包过大");return bytes.toByteArray();
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Integer> importBundle(String sourceId,byte[] bytes)throws IOException {
        var source=sources.get(sourceId);if(!source.type().equals("WEBDAV"))throw failure(400,"仅支持 WebDAV 来源");
        if(bytes.length>LIMIT)throw failure(413,"索引包最多 32 MiB");
        var document=readManifest(bytes);
        profiles.lockBindings();var profile=profiles.savedProfile(source.parserProfileId());metadata.prepare(source);
        var seen=new HashSet<String>();int added=0,skipped=0;
        for(var item:document.path("entries")){
            String path=item.path("path").asText();validatePath(path);if(!seen.add(path)||!KtvFilenameParser.supportsMediaFilename(path))throw failure(400,"歌曲路径重复或格式不支持");
            var snapshot=item.path("metadata");metadata.validateShared(snapshot);
            String id=UUID.nameUUIDFromBytes((sourceId+"\0"+path).getBytes(StandardCharsets.UTF_8)).toString();
            // Existing/manual data and retained tombstones are never overwritten by imported snapshots.
            if(!songs.find(id).isEmpty()||!mapper.resource(args("songId",id)).isEmpty()){skipped++;continue;}
            var parsed=new KtvFilenameParser().preview(path.substring(path.lastIndexOf('/')+1),profile.rules());
            Long size=item.has("size")?item.path("size").longValue():null;if(item.has("size")&&(!item.path("size").isIntegralNumber()||!item.path("size").canConvertToLong())||size!=null&&size<0)throw failure(400,"文件大小无效");
            songs.insert(new MediaSongRepository.Stored(id,sourceId,path,com.mynora.singverse.auth.crypto.AuthCrypto.hash(path),size,null,Instant.now().toString(),profile.id(),profile.revision(),parsed.selectedRule()==null?null:parsed.selectedRule().id(),json.writeValueAsString(parsed.result()),parsed.result().needsReview()));
            metadata.installShared(id,snapshot);added++;
        }
        return Map.of("imported",added,"skipped",skipped);
    }
    JsonNode readManifest(byte[] bytes)throws IOException {
        byte[] manifest=null;long expanded=0;int count=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                validatePath(entry.getName());if(++count>30001)throw failure(400,"索引包条目过多");
                byte[] value=zip.readNBytes((int)Math.min(LIMIT-expanded+1,LIMIT+1));expanded+=value.length;
                if(expanded>LIMIT)throw failure(413,"索引包展开后超过 32 MiB");
                if(entry.getName().equals("manifest.json")){if(manifest!=null)throw failure(400,"重复清单");manifest=value;}
            }
        }catch(ZipException error){throw failure(400,"索引包格式无效");}
        if(manifest==null)throw failure(400,"缺少 manifest.json");JsonNode document;try{document=json.readTree(manifest);}catch(IOException error){throw failure(400,"Invalid manifest JSON");}
        if(document==null||!document.path("format").asText().equals("singverse-media-index")||document.path("version").asInt()!=2||!document.path("entries").isArray()||document.path("entries").size()>5000)throw failure(400,"索引包版本或条目无效");
        return document;
    }
    private static final class LimitedZip extends ZipOutputStream {
        long expanded;
        LimitedZip(OutputStream out){super(out,StandardCharsets.UTF_8);}
        @Override public void write(byte[] data,int offset,int length)throws IOException{expanded+=length;if(expanded>LIMIT)throw failure(413,"索引包展开后超过 32 MiB");super.write(data,offset,length);}
    }
    static void validatePath(String path){if(path==null||path.isBlank()||path.length()>2048||path.startsWith("/")||path.contains("\\")||path.contains(":")||path.chars().anyMatch(Character::isISOControl)||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isBlank()||p.equals(".")||p.equals("..")))throw failure(400,"索引包路径无效");}
    private static String xml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static void write(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
}
