package com.mynora.singverse.media.transcoding;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface TranscodeMapper {
    Map<String, Object> find(@Param("id") String id);

    List<Map<String, Object>> list();

    List<Map<String, Object>> pending();

    List<Map<String, Object>> versions(
            @Param("song") String song, @Param("fingerprint") String fingerprint);

    int insert(@Param("row") Map<String, Object> row);

    int update(
            @Param("id") String id,
            @Param("state") String state,
            @Param("result") String result,
            @Param("error") String error,
            @Param("now") long now);

    int renew(@Param("id") String id, @Param("now") long now);

    int plan(@Param("id") String id, @Param("target") String target);

    int requeue(
            @Param("id") String id, @Param("execution") String execution, @Param("now") long now);

    int delete(@Param("id") String id);
}
