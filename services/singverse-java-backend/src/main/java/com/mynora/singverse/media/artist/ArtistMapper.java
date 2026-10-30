package com.mynora.singverse.media.artist;

import org.apache.ibatis.annotations.*;

import java.util.*;

@Mapper
public interface ArtistMapper {
    int lock();

    List<Map<String, Object>> legacy();

    List<Map<String, Object>> list();

    Map<String, Object> find(@Param("id") String id);

    int insert(
            @Param("id") String id,
            @Param("input") ArtistDtos.Save input,
            @Param("aliases") String aliases,
            @Param("hash") String hash,
            @Param("now") String now);

    int update(
            @Param("id") String id,
            @Param("input") ArtistDtos.Save input,
            @Param("aliases") String aliases,
            @Param("now") String now);

    List<Map<String, Object>> bindings();

    Map<String, Object> binding(@Param("song") String song, @Param("credit") String credit);

    int insertBinding(
            @Param("song") String song,
            @Param("credit") String credit,
            @Param("artist") String artist);

    int updateBinding(
            @Param("song") String song,
            @Param("credit") String credit,
            @Param("artist") String artist,
            @Param("revision") long revision);

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

    int adopt(@Param("id") String id, @Param("key") String key);

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

    List<Map<String, Object>> pending(@Param("now") String now);

    int fetchState(
            @Param("id") String id,
            @Param("state") String state,
            @Param("attempts") int attempts,
            @Param("after") String after,
            @Param("generation") long generation);

    int retry(@Param("id") String id);

    int imageSource(
            @Param("id") String id,
            @Param("kind") String kind,
            @Param("origin") String origin,
            @Param("provider") String provider,
            @Param("external") String external,
            @Param("url") String url);
}
