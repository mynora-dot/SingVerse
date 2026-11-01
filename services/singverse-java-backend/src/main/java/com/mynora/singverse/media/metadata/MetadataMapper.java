package com.mynora.singverse.media.metadata;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MetadataMapper {
    Map<String, Object> settings();
    int lock();
    int saveSettings(@Param("enabled") boolean enabled, @Param("providers") String providers,
            @Param("autoApply") boolean autoApply, @Param("threshold") double threshold, @Param("minInterval") int minInterval, @Param("maxInterval") int maxInterval, @Param("now") String now);
    int pauseActive(@Param("now") String now);
    int recoverItems(@Param("now") String now);
    List<Map<String, Object>> active();
    List<Map<String, Object>> latest();
    List<Map<String, Object>> batch(@Param("id") String id);
    int insertBatch(@Param("id") String id, @Param("mode") String mode, @Param("providers") String providers,
            @Param("autoApply") boolean autoApply, @Param("threshold") double threshold,
            @Param("skipped") int skipped, @Param("now") String now);
    int batchStatus(@Param("id") String id, @Param("expected") String expected,
            @Param("status") String status, @Param("now") String now);
    List<Map<String, Object>> targetSongs(@Param("ids") List<String> ids, @Param("all") boolean all, @Param("sourceId") String sourceId);
    int insertItem(@Param("id") String id, @Param("batchId") String batchId, @Param("songId") String songId,
            @Param("fingerprint") String fingerprint, @Param("title") String title,
            @Param("artists") String artists, @Param("now") String now);
    List<Map<String, Object>> counts(@Param("batchId") String batchId);
    List<Map<String, Object>> items(@Param("batchId") String batchId, @Param("status") String status,
            @Param("limit") int limit, @Param("offset") long offset);
    List<Map<String,Object>> viewItems(@Param("batchId") String batchId, @Param("status") String status, @Param("keyword") String keyword, @Param("sort") String sort, @Param("limit") int limit, @Param("offset") long offset);
    long viewCount(@Param("batchId") String batchId, @Param("status") String status, @Param("keyword") String keyword);
    long itemCount(@Param("batchId") String batchId, @Param("status") String status);
    List<Map<String, Object>> item(@Param("id") String id);
    int itemStatus(@Param("id") String id, @Param("expected") String expected, @Param("status") String status,
            @Param("candidates") String candidates, @Param("error") String error, @Param("now") String now);
    int insertCandidate(@Param("id") String id, @Param("songId") String songId,
            @Param("fingerprint") String fingerprint, @Param("track") String track,
            @Param("score") double score, @Param("now") String now);
    List<Map<String, Object>> candidate(@Param("id") String id);
    int deleteApplied(@Param("songId") String songId);
    int insertApplied(@Param("songId") String songId, @Param("sourceId") String sourceId,
            @Param("fingerprint") String fingerprint, @Param("track") String track,
            @Param("fields") String fields, @Param("origins") String origins, @Param("now") String now);
    int pruneCandidates(@Param("before") String before);
    int cleanupSource(@Param("sourceId") String sourceId);
    int cleanupOrphans(@Param("sourceId") String sourceId);
}
