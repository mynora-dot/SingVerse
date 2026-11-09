package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import java.nio.file.*;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Single download destination. Called inside the media-source transaction when bindings change. */
@Service
public class LxTargetService {
    private final LxMapper mapper;
    public LxTargetService(LxMapper mapper) { this.mapper=mapper; }
    /** Acquire before any consistent read so MySQL checks use the state after the previous owner committed. */
    public void lockBindings() { mapper.lockSettings(); }
    public void change(String id,String type,String path,Boolean requested,boolean removing) {
        mapper.lockSettings();
        var current=mapper.settings();
        String bound=str(current,"source_id");
        boolean isBound=id.equals(bound);
        boolean selected=requested==null?isBound:Boolean.TRUE.equals(requested);
        boolean changing=isBound&&(removing||!selected||!path.equals(str(current,"source_path")));
        if(changing&&mapper.activeSource(args("sourceId",id))>0)
            throw conflict("下载仍在进行，请先结束或取消该媒体库的下载任务");
        if(selected&&!removing) {
            if(!"LOCAL".equals(type))throw bad("洛雪下载保存位置必须为本地目录");
            writable(path,true);
            if(!bound.isEmpty()&&!isBound&&mapper.activeSource(args("sourceId",bound))>0)
                throw conflict("原保存位置仍有下载任务，请先结束或取消任务");
            mapper.bind(args("sourceId",id));
        } else if(isBound) mapper.bind(args("sourceId",null));
    }
    public Map<String,Object> require() {
        var value=mapper.settings();
        if(str(value,"source_id").isEmpty()||!"LOCAL".equals(str(value,"source_type")))
            throw conflict("请先在媒体库中设置洛雪下载保存位置");
        writable(str(value,"source_path"),true);
        return value;
    }
    public Map<String,Object> status() {
        var value=mapper.settings();
        String reason="";
        try {
            if(str(value,"source_id").isEmpty()||!"LOCAL".equals(str(value,"source_type"))) reason="尚未设置下载保存位置";
            else writable(str(value,"source_path"),false);
        } catch(org.springframework.web.server.ResponseStatusException e) { reason=e.getReason(); }
        return args("id",value.get("source_id"),"name",value.get("source_name"),"path",value.get("source_path"),
                "ready",reason.isEmpty(),"reason",reason);
    }
    public static Path writable(String location,boolean probe) {
        try {
            Path path=Path.of(location);
            if(!path.isAbsolute()||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path)
                    ||!Files.isWritable(path))throw new java.io.IOException();
            if(probe) {
                Path temporary=Files.createTempFile(path,".singverse-write-check-",".tmp");
                Files.delete(temporary);
            }
            return path;
        } catch(Exception error) { throw conflict("下载目录不存在或不可写，请检查 NAS 挂载和目录权限"); }
    }
}
