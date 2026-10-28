package com.mynora.singverse.media.metadata;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface SingleReviewMapper {
    List<Map<String,Object>> receipt(@Param("id") String id);
    int receiptSave(@Param("id") String id, @Param("songId") String songId, @Param("hash") String hash, @Param("response") String response, @Param("now") String now);
    List<Map<String,Object>> asset(@Param("id") String id);
    int assetSave(@Param("id") String id, @Param("songId") String songId, @Param("fingerprint") String fingerprint,
            @Param("mime") String mime, @Param("width") int width, @Param("height") int height, @Param("now") String now);
    List<Map<String,Object>> oldAssets(@Param("before") String before);
    long references(@Param("pattern") String pattern);
    int assetDelete(@Param("id") String id);
    int pruneReceipts(@Param("before") String before);
}
