package com.mynora.singverse.media.playlist;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface PlaylistMapper {
    int lock();

    List<Map<String, Object>> list();

    Map<String, Object> find(@Param("id") String id);

    int insert(
            @Param("id") String id,
            @Param("name") String name,
            @Param("description") String description,
            @Param("songs") String songs,
            @Param("hash") String hash,
            @Param("now") String now);

    int update(
            @Param("id") String id,
            @Param("name") String name,
            @Param("description") String description,
            @Param("songs") String songs,
            @Param("revision") long revision,
            @Param("now") String now);

    int remove(@Param("id") String id, @Param("revision") long revision);
}
