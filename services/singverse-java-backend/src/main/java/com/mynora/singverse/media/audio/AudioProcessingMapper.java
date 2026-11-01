package com.mynora.singverse.media.audio;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AudioProcessingMapper {
    List<String> songIds(@Param("cursor") String cursor, @Param("source") String source);
    int insertBatch(@Param("id") String id, @Param("backend") String backend, @Param("now") long now);
    int insertItem(@Param("row") Map<String,Object> row);
    Map<String,Object> batch(@Param("id") String id);
    List<Map<String,Object>> batches(@Param("limit") int limit,@Param("offset") long offset);
    long batchCount();
    List<Map<String,Object>> counts(@Param("id") String id);
    List<Map<String,Object>> items(@Param("id") String id,@Param("limit") int limit,@Param("offset") long offset);
    List<Map<String,Object>> pending();
    Map<String,Object> item(@Param("id") String id,@Param("song") String song);
    List<String> failedSongs(@Param("id") String id);
    int start(@Param("id") String id,@Param("now") long now);
    int cancel(@Param("id") String id,@Param("now") long now);
    int finish(@Param("id") String id,@Param("now") long now);
    int updateItem(@Param("batch") String batch,@Param("song") String song,@Param("state") String state,@Param("job") String job,@Param("error") String error,@Param("now") long now);
    int skipCancelled(@Param("id") String id,@Param("now") long now);
    int collectPreviews(@Param("before") long before);
    int collectPreviewItems(@Param("before") long before);
}
