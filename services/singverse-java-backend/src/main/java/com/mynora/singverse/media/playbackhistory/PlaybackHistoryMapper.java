package com.mynora.singverse.media.playbackhistory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlaybackHistoryMapper {
    List<Map<String, Object>> history(
            @Param("filter") PlaybackHistoryService.Filter filter,
            @Param("offset") long offset,
            @Param("size") int size);

    long countHistory(@Param("filter") PlaybackHistoryService.Filter filter);

    List<Map<String, Object>> popular(
            @Param("filter") PlaybackHistoryService.Filter filter,
            @Param("offset") long offset,
            @Param("size") int size);

    long countPopular(@Param("filter") PlaybackHistoryService.Filter filter);

    Map<String, Object> summary(@Param("filter") PlaybackHistoryService.Filter filter);
}
