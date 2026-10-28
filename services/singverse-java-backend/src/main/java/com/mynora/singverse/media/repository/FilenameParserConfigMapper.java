package com.mynora.singverse.media.repository;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL is defined in the corresponding classpath mapper XML. */
@Mapper
public interface FilenameParserConfigMapper {
    List<Map<String, Object>> read();
    int lockBindings();
    int insert(@Param("encoded") String encoded, @Param("updatedAt") String updatedAt);
    int update(
            @Param("encoded") String encoded,
            @Param("updatedAt") String updatedAt,
            @Param("revision") long revision);
}
