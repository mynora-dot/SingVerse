package com.mynora.singverse.tv;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TvHomeMapper {
    Map<String, Object> settings();
    List<Map<String, Object>> banners();
    Map<String, Object> image(@Param("id") String id);
    int lock();
    int save(@Param("v") TvHomeSettingsService.Settings settings);
    int bump();
    int insert(@Param("id") String id, @Param("label") String label, @Param("order") int order,
            @Param("version") String version, @Param("bytes") byte[] bytes);
    int updateBanner(@Param("v") TvHomeSettingsService.Banner banner, @Param("order") int order);
    int remove(@Param("id") String id);
}
