package com.mynora.singverse.media.webdav;

import com.mynora.singverse.media.repository.MediaSongRepository.Stored;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScanTaskMapper {
    int recover(@Param("now") String now);
    int prune(@Param("before") String before);
    int clearAbandonedSongs();
    int clearAbandonedDirectories();
    int activeCount();
    List<Map<String, Object>> activeSource(@Param("sourceId") String sourceId);
    List<Map<String, Object>> find(@Param("id") String id);
    List<Map<String, Object>> latest(@Param("sourceId") String sourceId);
    int insert(@Param("id") String id, @Param("sourceId") String sourceId, @Param("now") String now);
    int start(@Param("id") String id, @Param("now") String now);
    int cancel(@Param("id") String id);
    int committing(@Param("id") String id);
    int finish(@Param("id") String id, @Param("status") String status,
            @Param("error") String error, @Param("now") String now);
    int progress(@Param("id") String id, @Param("directories") long directories,
            @Param("entries") long entries, @Param("total") int total, @Param("matched") int matched,
            @Param("review") int review, @Param("skipped") long skipped);
    int directory(@Param("taskId") String taskId, @Param("hash") String hash,
            @Param("path") String path, @Param("depth") int depth);
    List<Map<String, Object>> nextDirectory(@Param("taskId") String taskId);
    long hasDirectory(@Param("taskId") String taskId, @Param("hash") String hash);
    int processed(@Param("taskId") String taskId, @Param("hash") String hash);
    int stage(@Param("taskId") String taskId, @Param("songs") List<Stored> songs);
    List<String> stagedIds(@Param("taskId") String taskId, @Param("ids") List<String> ids);
    int clearSongs(@Param("taskId") String taskId);
    int clearDirectories(@Param("taskId") String taskId);
    int publish(@Param("taskId") String taskId);
}
