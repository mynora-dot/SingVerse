package com.mynora.singverse.media.repository;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MediaSongMapper {
    List<Map<String, Object>> list(@Param("sourceId") String sourceId,
            @Param("limit") int limit, @Param("offset") long offset);
    List<Map<String, Object>> find(@Param("id") String id);
    long count(@Param("sourceId") String sourceId);
    List<Map<String, Object>> search(@Param("sourceId") String sourceId, @Param("keyword") String keyword,
            @Param("review") Integer review, @Param("limit") int limit, @Param("offset") long offset,
            @Param("tagGroups") Map<String,List<String>> tagGroups, @Param("unclassified") boolean unclassified);
    long searchCount(@Param("sourceId") String sourceId, @Param("keyword") String keyword, @Param("review") Integer review, @Param("tagGroups") Map<String,List<String>> tagGroups, @Param("unclassified") boolean unclassified);
    int delete(@Param("sourceId") String sourceId);
    int insert(@Param("song") MediaSongRepository.Stored song);
}
