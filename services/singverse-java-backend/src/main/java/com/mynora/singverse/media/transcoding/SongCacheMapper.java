package com.mynora.singverse.media.transcoding;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SongCacheMapper {
    Map<String, Object> settings();
    int initialize(@Param("bytes") long bytes);
    int saveSettings(@Param("v") Map<String, Object> settings);
    List<Map<String, Object>> entries();
    Map<String, Object> find(@Param("id") String id);
    Map<String, Object> byKey(@Param("key") String key);
    int insert(@Param("v") Map<String, Object> entry);
    int delete(@Param("id") String id);
    int hit(@Param("id") String id, @Param("now") long now);
    int touch(@Param("id") String id, @Param("now") long now);
    Map<String, Object> lease(@Param("session") String session);
    int active(@Param("id") String id, @Param("since") long since);
    int bind(@Param("session") String session, @Param("id") String id, @Param("now") long now);
    int renew(@Param("session") String session, @Param("now") long now);
    int release(@Param("session") String session);
    int releaseCache(@Param("id") String id);
    int expire(@Param("since") long since);
}
