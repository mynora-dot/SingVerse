package com.mynora.singverse.background;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BackgroundTaskMapper {
    List<Map<String, Object>> list(Map<String, Object> args);
    long count(Map<String, Object> args);
    List<Map<String, Object>> children(Map<String, Object> args);
    long childCount(Map<String, Object> args);
    List<Map<String, Object>> childStates(Map<String, Object> args);
    List<Map<String, Object>> memberships(Map<String, Object> args);
    Map<String, Object> find(Map<String, Object> args);
    List<Map<String, Object>> items(Map<String, Object> args);
    long itemCount(Map<String, Object> args);
    List<Map<String, Object>> itemStates(Map<String, Object> args);
    int insert(Map<String, Object> args);
    int update(Map<String, Object> args);
    Map<String, Object> findItem(Map<String, Object> args);
    int insertItem(Map<String, Object> args);
    int updateItem(Map<String, Object> args);
    List<Map<String, Object>> active(Map<String, Object> args);
    List<Map<String, Object>> expired(Map<String, Object> args);
    int deleteItems(Map<String, Object> args);
    int deleteTask(Map<String, Object> args);
    int interruptLocal(Map<String, Object> args);
    int lockExpired(Map<String, Object> args);
    Map<String, Object> localSource(Map<String, Object> args);
    List<Map<String, Object>> source(Map<String, Object> args);
    List<Map<String, Object>> sourceKeys(Map<String, Object> args);
    List<Map<String, Object>> sourceCounts(Map<String, Object> args);
    List<Map<String, Object>> sourceItems(Map<String, Object> args);
    List<Map<String, Object>> owner(Map<String, Object> args);
    List<Map<String, Object>> parents(Map<String, Object> args);
}
