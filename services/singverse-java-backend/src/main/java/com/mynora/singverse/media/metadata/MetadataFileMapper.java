package com.mynora.singverse.media.metadata;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface MetadataFileMapper {
 int touch(Map<String,Object> args);
 int enqueue(Map<String,Object> args);
 int enqueueSource(Map<String,Object> args);
 int touchPath(Map<String,Object> args);
 int enqueuePath(Map<String,Object> args);
 int failures(Map<String,Object> args);
 int retrySource(Map<String,Object> args);
 int attachments(Map<String,Object> args);
 List<Map<String,Object>> previous(Map<String,Object> args);
 List<Map<String,Object>> pending(Map<String,Object> args);
 int complete(Map<String,Object> args);
 int failed(Map<String,Object> args);
 int pendingSource(Map<String,Object> args);
}
