package com.mynora.singverse.ai;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiMapper {
    int lock();
    List<Map<String,Object>> services(@Param("kind") String kind);
    Map<String,Object> service(@Param("id") String id);
    int insert(@Param("id") String id, @Param("kind") String kind, @Param("v") AiDtos.ServiceInput v,
            @Param("secret") String secret, @Param("purpose") String purpose);
    int update(@Param("id") String id, @Param("v") AiDtos.ServiceInput v,
            @Param("secret") String secret, @Param("purpose") String purpose);
    int remove(@Param("id") String id);
    int references(@Param("id") String id);
    List<Map<String,Object>> features();
    Map<String,Object> feature(@Param("id") String id);
    int saveFeature(@Param("v") AiDtos.Feature v);
    List<Map<String,Object>> secrets();
}
