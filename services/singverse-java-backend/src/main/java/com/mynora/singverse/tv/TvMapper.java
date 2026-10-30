package com.mynora.singverse.tv;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface TvMapper {
    Map<String, Object> find(@Param("id") String id);

    Map<String, Object> secret(@Param("hash") String hash);

    List<Map<String, Object>> list(@Param("kind") String kind);

    int insert(
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("hash") String hash,
            @Param("document") String document);

    int save(
            @Param("id") String id,
            @Param("document") String document,
            @Param("revision") long revision);

    int lock(@Param("id") String id);

    List<Map<String, Object>> library();

    int delete(@Param("id") String id);
}
