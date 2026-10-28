package com.mynora.singverse.media.repository;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL is defined in the corresponding classpath mapper XML. */
@Mapper
public interface MediaSourceMapper {
    int schedule(@Param("id") String id, @Param("enabled") boolean enabled, @Param("cron") String cron, @Param("next") String next);
    List<Map<String,Object>> due(@Param("now") String now);
    int claim(@Param("id") String id, @Param("expected") String expected, @Param("next") String next);
    int scanResult(@Param("id") String id, @Param("now") String now, @Param("status") String status, @Param("error") String error);
    int countEncryptedPasswords();
    List<String> encryptedPasswords();
    List<Map<String, Object>> list(@Param("limit") int limit, @Param("offset") long offset);
    long count();
    List<Map<String, Object>> search(@Param("keyword") String keyword, @Param("type") String type,
            @Param("profileId") String profileId, @Param("limit") int limit, @Param("offset") long offset);
    long searchCount(@Param("keyword") String keyword, @Param("type") String type, @Param("profileId") String profileId);
    List<Map<String, Object>> find(@Param("id") String id);
    int delete(@Param("id") String id);
    int touch(@Param("id") String id, @Param("expected") String expected, @Param("now") String now);
    List<Map<String, Object>> references(@Param("profileId") String profileId);
    int insert(
            @Param("id") String id,
            @Param("name") String name,
            @Param("type") String type,
            @Param("location") String location,
            @Param("locationHash") String locationHash,
            @Param("username") String username,
            @Param("encrypted") String encrypted,
            @Param("profileId") String profileId,
            @Param("createdAt") String createdAt,
            @Param("updatedAt") String updatedAt);
    int update(
            @Param("name") String name,
            @Param("location") String location,
            @Param("locationHash") String locationHash,
            @Param("username") String username,
            @Param("encrypted") String encrypted,
            @Param("profileId") String profileId,
            @Param("updatedAt") String updatedAt,
            @Param("id") String id);
}
