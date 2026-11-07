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
    int addBytes(@Param("id") String id, @Param("bytes") long bytes, @Param("now") long now);
    int repairOriginalBytes(@Param("id") String id, @Param("bytes") long bytes, @Param("residue") long residue, @Param("now") long now);
    int target(@Param("id") String id, @Param("target") String target);
    Map<String, Object> lease(@Param("session") String session);
    int active(@Param("id") String id, @Param("since") long since);
    int bind(@Param("session") String session, @Param("id") String id, @Param("now") long now);
    int renew(@Param("session") String session, @Param("now") long now);
    int release(@Param("session") String session);
    int releaseCache(@Param("id") String id);
    int expire(@Param("since") long since);
    Map<String, Object> totals();
    List<Map<String, Object>> paths();
    List<Map<String, Object>> page(@Param("offset") long offset, @Param("size") int size, @Param("since") long since, @Param("now") long now);
    List<Map<String, Object>> candidates(@Param("since") long since, @Param("now") long now);
    Map<String, Object> profile(@Param("song") String song);
    int ensureProfile(@Param("song") String song);
    int lockProfile(@Param("song") String song);
    int pin(@Param("song") String song, @Param("pinned") int pinned, @Param("revision") long revision);
    List<Map<String, Object>> pins(@Param("offset") long offset, @Param("size") int size);
    long pinCount();
    int deletingForSong(@Param("song") String song);
    int playing(@Param("song") String song, @Param("now") long now);
    int activeSong(@Param("song") String song, @Param("since") long since);
    boolean statisticsReady();
    int claimDelete(@Param("id") String id, @Param("token") String token);
    int failedDelete(@Param("id") String id, @Param("retry") long retry, @Param("error") String error);
    List<Map<String, Object>> pendingDeletes(@Param("now") long now);
    int insertJob(@Param("v") Map<String, Object> job);
    int updateJob(@Param("id") String id, @Param("state") String state, @Param("result") String result, @Param("now") long now);
    Map<String, Object> job(@Param("id") String id);
    List<Map<String, Object>> interruptedJobs();
    int pruneJobs(@Param("before") long before);
}
