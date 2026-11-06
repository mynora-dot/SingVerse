package com.mynora.singverse.media.missingfeedback;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MissingFeedbackMapper {
    int lock();

    Map<String, Object> find(@Param("id") String id);

    List<String> currentSongIds(@Param("ids") List<String> ids);

    long count(@Param("query") String query, @Param("status") String status);

    List<Map<String, Object>> list(
            @Param("query") String query,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("size") int size);

    int insert(Map<String, Object> row);

    int update(Map<String, Object> row);
}
