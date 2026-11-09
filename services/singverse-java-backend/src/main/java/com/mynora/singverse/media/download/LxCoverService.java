package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.metadata.MetadataCoverService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Same-origin covers. The browser never fetches third-party catalog URLs. */
@Service
public class LxCoverService {
    private record Reference(String url,long expires) {}
    private final Map<String,Reference> references=new ConcurrentHashMap<>();
    private final Map<String,MetadataCoverService.Cover> cache=new LinkedHashMap<>();
    private final LxNetwork network;
    private final LxMapper mapper;
    public LxCoverService(LxNetwork network,LxMapper mapper){this.network=network;this.mapper=mapper;}
    public String reference(String url){if(url==null||url.isBlank())return "";LxNetwork.checked(url,false);long now=System.currentTimeMillis();if(references.size()>=2000){references.entrySet().removeIf(e->e.getValue().expires<now);if(references.size()>=2000)references.clear();}String id=AuthCrypto.hash(url);references.put(id,new Reference(url,now+3600_000));return "/api/v1/lx-downloads/covers/"+id;}
    public MetadataCoverService.Cover get(String id) {
        Reference ref=references.get(id);if(ref==null||ref.expires<System.currentTimeMillis())throw missing();
        synchronized(cache){var prior=cache.get(id);if(prior!=null)return prior;}
        var reply=network.request(ref.url,"GET",Map.of(),null,2_000_000,false,"lx-cover-"+UUID.randomUUID());if(reply.status()!=200)throw missing();var cover=validate(reply.bytes());
        synchronized(cache){if(cache.size()>=16)cache.remove(cache.keySet().iterator().next());cache.put(id,cover);}return cover;
    }
    public MetadataCoverService.Cover song(String songId) {
        var asset=mapper.songAsset(args("songId",songId));if(asset==null)throw missing();
        var settings=mapper.assetsForTrack(args("trackKey",asset.get("track_key"))).stream().filter(row->songId.equals(str(row,"song_id"))).findFirst().orElseThrow(LxCoverService::missing);
        Path root=Path.of(str(settings,"source_path")),audio=root.resolve(str(asset,"relative_path")).normalize();
        if(!audio.startsWith(root))throw missing();String stem=audio.getFileName().toString().replaceFirst("\\.[^.]+$","");
        for(String extension:List.of("jpg","png","webp"))try{Path path=audio.resolveSibling(stem+"."+extension);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&path.toRealPath().startsWith(root)){try(var in=Files.newInputStream(path)){return validate(in.readNBytes(2_000_001));}}}catch(Exception ignored){}
        throw missing();
    }
    /** Migu publishes WebP covers. Bound the still-image container and dimensions before serving it. */
    static MetadataCoverService.Cover validate(byte[] bytes) {
        if(bytes.length<12||!text(bytes,0,4).equals("RIFF")||!text(bytes,8,4).equals("WEBP"))return MetadataCoverService.validate(bytes);
        if(bytes.length>2_000_000||little(bytes,4,4)+8!=bytes.length)throw missing();
        boolean image=false;int offset=12;
        while(offset<bytes.length) {
            if(bytes.length-offset<8)throw missing();String chunk=text(bytes,offset,4);long size=little(bytes,offset+4,4);long end=offset+8L+size+(size&1);
            if(end>bytes.length)throw missing();int start=offset+8,width=0,height=0;
            switch(chunk) {
                case "VP8 " -> {if(size<10||(bytes[start]&1)!=0||(bytes[start+3]&255)!=157||bytes[start+4]!=1||bytes[start+5]!=42)throw missing();width=(int)little(bytes,start+6,2)&0x3fff;height=(int)little(bytes,start+8,2)&0x3fff;image=true;}
                case "VP8L" -> {if(size<5||bytes[start]!=47)throw missing();long bits=little(bytes,start+1,4);if((bits>>>29)!=0)throw missing();width=(int)(bits&0x3fff)+1;height=(int)((bits>>>14)&0x3fff)+1;image=true;}
                case "VP8X" -> {if(size!=10||(bytes[start]&2)!=0)throw missing();width=(int)little(bytes,start+4,3)+1;height=(int)little(bytes,start+7,3)+1;}
                case "ANIM","ANMF" -> throw missing();
                default -> {}
            }
            if((chunk.equals("VP8 ")||chunk.equals("VP8L")||chunk.equals("VP8X"))&&(width<1||height<1||width>4096||height>4096))throw missing();
            offset=(int)end;
        }
        if(!image)throw missing();return new MetadataCoverService.Cover(bytes,org.springframework.http.MediaType.parseMediaType("image/webp"));
    }
    private static long little(byte[] bytes,int start,int size){long value=0;for(int i=0;i<size;i++)value|=(long)(bytes[start+i]&255)<<(8*i);return value;}
    private static String text(byte[] bytes,int start,int size){return new String(bytes,start,size,java.nio.charset.StandardCharsets.US_ASCII);}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"没有可用的封面");}
}
