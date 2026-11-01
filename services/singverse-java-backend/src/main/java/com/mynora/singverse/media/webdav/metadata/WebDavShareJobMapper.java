package com.mynora.singverse.media.webdav.metadata;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface WebDavShareJobMapper {
int insert(Map<String,Object> args);
int snapshot(Map<String,Object> args);
int count(Map<String,Object> args);
int sourceCount(Map<String,Object> args);
int snapshotCount(Map<String,Object> args);
List<Map<String,Object>> find(Map<String,Object> args);
List<Map<String,Object>> latest(Map<String,Object> args);
List<Map<String,Object>> pending(Map<String,Object> args);
List<Map<String,Object>> page(Map<String,Object> args);
List<Map<String,Object>> baseEntry(Map<String,Object> args);
List<Map<String,Object>> deleted(Map<String,Object> args);
int progress(Map<String,Object> args);
List<Map<String,Object>> asset(Map<String,Object> args);
int assetInsert(Map<String,Object> args);
List<Map<String,Object>> receipt(Map<String,Object> args);
int receiptInsert(Map<String,Object> args);
int receiptUpdate(Map<String,Object> args);
int lock(Map<String,Object> args);
List<Map<String,Object>> active(Map<String,Object> args);
List<Map<String,Object>> expired(Map<String,Object> args);
int deleteEntries(Map<String,Object> args);
int deleteAssets(Map<String,Object> args);
int deleteJob(Map<String,Object> args);
int receiptLock(Map<String,Object> args);
}
