package com.mynora.singverse.media.audio;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AudioRoleMapper {
    int insertClaim(@Param("song") String song,@Param("claim") String claim);
    String claim(@Param("song") String song);
    Map<String,Object> role(@Param("song") String song);
    int insertRole(@Param("row") Map<String,Object> row);
    int updateRole(@Param("row") Map<String,Object> row);
    Map<String,Object> job(@Param("id") String id);
    List<Map<String,Object>> pending();
    List<String> candidates(@Param("cursor") String cursor);
    List<Map<String,Object>> forSong(@Param("song") String song,@Param("fingerprint") String fingerprint);
    List<Map<String,Object>> queuedAnalyze();
    int insertJob(@Param("row") Map<String,Object> row);
    int updateJob(@Param("id") String id,@Param("state") String state,@Param("result") String result,@Param("error") String error,@Param("now") long now);
}
