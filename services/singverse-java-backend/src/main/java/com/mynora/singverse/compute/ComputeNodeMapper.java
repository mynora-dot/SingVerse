package com.mynora.singverse.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ComputeNodeMapper {
    Map<String,Object> settings();
    List<String> secrets();
    int save(@Param("mode") String mode, @Param("endpoint") String endpoint, @Param("secret") String secret,
             @Param("mappings") String mappings, @Param("revision") long revision);
}
