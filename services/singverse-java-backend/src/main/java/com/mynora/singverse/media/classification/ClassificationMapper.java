package com.mynora.singverse.media.classification;

import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ClassificationMapper {
    Map<String,Object> settings();
    List<String> taskConfigs();
    int lock();
    int cooldown(@Param("next") String next);
    int saveSettings(@Param("c") ClassificationDtos.ModelConfig c);
    List<Map<String,Object>> tags();
    int insertTag(@Param("id") String id, @Param("dimension") String dimension, @Param("name") String name, @Param("aliases") String aliases, @Param("active") boolean active);
    int updateTag(@Param("id") String id, @Param("name") String name, @Param("aliases") String aliases, @Param("active") boolean active, @Param("revision") long revision);
    List<String> songIds(@Param("limit") int limit);
    Map<String,Object> state(@Param("id") String id);
    int insertState(@Param("id") String id, @Param("state") String state);
    int updateState(@Param("id") String id, @Param("revision") long revision, @Param("state") String state);
    int clearAssignments(@Param("id") String id);
    int assign(@Param("id") String id, @Param("dimension") String dimension, @Param("tag") String tag);
    int insertHistory(@Param("id") String id, @Param("song") String song, @Param("task") String task, @Param("origin") String origin, @Param("before") String before, @Param("after") String after, @Param("revision") long revision, @Param("now") String now);
    List<Map<String,Object>> history(@Param("id") String id, @Param("limit") int limit, @Param("offset") long offset);
    Map<String,Object> historyOne(@Param("id") String id);
    int reverted(@Param("id") String id);
    Map<String,Object> receipt(@Param("id") String id);
    int receiptSave(@Param("id") String id, @Param("hash") String hash, @Param("response") String response);
    int insertTask(@Param("id") String id, @Param("config") String config, @Param("vocabulary") String vocabulary, @Param("dimensions") String dimensions, @Param("automatic") String automatic, @Param("now") String now);
    int insertItem(@Param("id") String id, @Param("task") String task, @Param("song") String song, @Param("input") String input, @Param("version") String version, @Param("revision") long revision, @Param("now") String now);
    Map<String,Object> task(@Param("id") String id);
    List<Map<String,Object>> tasks(@Param("limit") int limit, @Param("offset") long offset);
    List<Map<String,Object>> items(@Param("task") String task, @Param("status") String status, @Param("limit") int limit, @Param("offset") long offset);
    long itemCount(@Param("task") String task, @Param("status") String status);
    List<Map<String,Object>> counts(@Param("id") String id);
    Map<String,Object> item(@Param("id") String id);
    Map<String,Object> next(@Param("now") String now);
    int itemState(@Param("id") String id, @Param("status") String status, @Param("suggestion") String suggestion, @Param("error") String error, @Param("revision") long revision, @Param("now") String now);
    int retry(@Param("id") String id, @Param("input") String input, @Param("version") String version, @Param("revision") long revision, @Param("now") String now);
    int taskState(@Param("id") String id, @Param("status") String status, @Param("next") String next, @Param("now") String now);
    int cancelItems(@Param("id") String id, @Param("now") String now);
    int recoverItems(@Param("now") String now);
    int recoverTasks(@Param("now") String now);
    int pauseAll(@Param("now") String now);
}
