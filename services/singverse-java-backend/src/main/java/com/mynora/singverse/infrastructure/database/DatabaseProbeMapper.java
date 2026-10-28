package com.mynora.singverse.infrastructure.database;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL is defined in the corresponding classpath mapper XML. */
@Mapper
public interface DatabaseProbeMapper {
    int check();
}
