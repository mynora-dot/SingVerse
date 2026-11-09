package com.mynora.singverse.media.download;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class LxTypes {
    private LxTypes() {}
    public static final Map<String,String> PLATFORMS;
    static {
        var platforms=new LinkedHashMap<String,String>();
        platforms.put("wy","网易云音乐");platforms.put("tx","QQ 音乐");platforms.put("kg","酷狗音乐");
        platforms.put("kw","酷我音乐");platforms.put("mg","咪咕音乐");PLATFORMS=Collections.unmodifiableMap(platforms);
    }
    public static final List<String> QUALITIES = List.of("flac24bit","flac","320k","128k");
    public record Track(String id,String platform,String remoteId,String title,List<String> artists,String album,
            Long durationMs,String coverUrl,JsonNode musicInfo) {}
    public record TrackView(String id,String platform,String title,List<String> artists,String album,Long durationMs,
            String coverUrl,boolean inLibrary,boolean downloading,String songId) {}
    public record SourceInput(String name,String script,String url,String environment) {}
    public record BatchInput(List<String> trackIds,String quality,String name) {}
    public record PreviewInput(String url,int page) {}
    public static Map<String,Object> args(Object... values) {
        var map=new HashMap<String,Object>();
        for(int i=0;i<values.length;i+=2)map.put(values[i].toString(),values[i+1]);
        return map;
    }
    public static String str(Map<String,Object> row,String key) { return row==null||row.get(key)==null?"":row.get(key).toString(); }
    public static long number(Map<String,Object> row,String key) { return row.get(key) instanceof Number n?n.longValue():0; }
    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    public static String clean(String value) {
        if(value==null)return "操作未完成，请重试";
        String text=value.replaceAll("(?i)https?://[^\\s<>\"']+","[地址已隐藏]")
                .replaceAll("(?i)(?:token|key|secret|password|authorization|cookie)[\"']?\\s*[=:]\\s*[\"']?[^\\s,;\"'}]+","[凭据已隐藏]")
                .replaceAll("[\\r\\n\\x00-\\x1f]"," ");
        return text.substring(0,Math.min(500,text.length()));
    }
    static String sourceFailure(String value) {
        // A source can include bearer tokens in arbitrary error text. Only return fixed user-facing categories.
        String message=value==null?"":value.toLowerCase(Locale.ROOT);
        if(message.matches("(?s).*(音质|quality|not.?found|不支持|不存在|无损|未找到|没有找到).*"))return "当前音质不可用，请更换音质、歌曲版本或音源";
        return "音源解析失败，请重试或更换音源";
    }
}
