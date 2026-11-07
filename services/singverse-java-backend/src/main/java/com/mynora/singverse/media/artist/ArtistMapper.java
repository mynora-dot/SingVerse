package com.mynora.singverse.media.artist;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface ArtistMapper {
    int lock();


    List<Map<String, Object>> list();

    Map<String, Object> find(@Param("id") String id);

    int category(@Param("id") String id, @Param("type") String type, @Param("revision") long revision, @Param("now") String now);

    int insert(@Param("id") String id, @Param("name") String name,
            @Param("hash") String hash, @Param("now") String now);

    Map<String, Object> image(@Param("id") String id, @Param("kind") String kind);

    int insertImage(
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("version") String version,
            @Param("bytes") byte[] bytes);

    int updateImage(
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("version") String version,
            @Param("bytes") byte[] bytes);

    int removeImage(@Param("id") String id, @Param("kind") String kind);

    Map<String, Object> byName(@Param("key") String key);


    Map<String, Object> override(@Param("source") String source, @Param("path") String path);

    int deleteOverride(@Param("source") String source, @Param("path") String path);

    int insertOverride(
            @Param("source") String source,
            @Param("path") String path,
            @Param("value") String value,
            @Param("revision") long revision);

    int clearSource(@Param("source") String source);

    int policy(@Param("id") String id, @Param("kind") String kind);

    int deletePolicy(@Param("id") String id, @Param("kind") String kind);

    int insertPolicy(
            @Param("id") String id, @Param("kind") String kind, @Param("blocked") boolean blocked);



    int retry(@Param("id") String id);

    int imageSource(
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("origin") String origin,
            @Param("provider") String provider,
            @Param("external") String external,
            @Param("url") String url);
    int attribution(@Param("id") String id, @Param("kind") String kind, @Param("value") String value);
    int ensureFetch(@Param("id") String id, @Param("kind") String kind);
    List<Map<String,Object>> pendingImage(@Param("now") String now);
    Map<String,Object> imageFetch(@Param("id") String id,@Param("kind") String kind);
    int imageCandidates(@Param("id") String id,@Param("kind") String kind,@Param("value") String value,@Param("generation") long generation);
    int imageFetchState(@Param("id") String id,@Param("kind") String kind,@Param("state") String state,@Param("attempts") int attempts,@Param("after") String after,@Param("generation") long generation);
    int retryImage(@Param("id") String id,@Param("kind") String kind);
    String fanartKey();
    int saveFanartKey(@Param("encrypted") String encrypted);
    int requeueMissingBackgrounds();
}
