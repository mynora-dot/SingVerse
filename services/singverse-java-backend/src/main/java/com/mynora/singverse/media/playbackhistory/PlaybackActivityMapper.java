package com.mynora.singverse.media.playbackhistory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlaybackActivityMapper {
    int ensureProfile(@Param("song") String song);
    int lockProfile(@Param("song") String song);
    Map<String, Object> profile(@Param("song") String song);
    Map<String, Object> currentProfile(@Param("song") String song, @Param("mysql") boolean mysql);
    int updateProfile(@Param("song") String song, @Param("lastPlayed") Long lastPlayed,
            @Param("heat") double heat, @Param("heatAt") Long heatAt);
    int insertAttempt(@Param("row") Map<String, Object> row);
    Map<String, Object> attempt(@Param("id") String id);
    Map<String, Object> currentAttempt(@Param("id") String id, @Param("mysql") boolean mysql);
    int updateAttempt(@Param("row") Map<String, Object> row);
    int insertHistory(@Param("row") Map<String, Object> row);
    int updateHistory(@Param("row") Map<String, Object> row);
    Map<String, Object> history(@Param("id") String id);
    Map<String, Object> currentHistory(@Param("id") String id, @Param("mysql") boolean mysql);
    List<Long> appliedHeatTimes(@Param("song") String song, @Param("current") String current, @Param("mysql") boolean mysql);
    int markAggregated(@Param("id") String id, @Param("effectiveAt") Long effectiveAt);
    List<Map<String, Object>> unpreparedHistory(@Param("limit") int limit);
    int prepareHistory(@Param("id") String id, @Param("effectiveAt") Long effectiveAt);
    List<Map<String, Object>> pendingHistory(@Param("limit") int limit);
    List<Map<String, Object>> expired(@Param("now") long now, @Param("limit") int limit);
    Map<String, Object> aggregationState();
    int checkpoint(@Param("ready") int ready, @Param("phase") String phase,
            @Param("started") long started, @Param("id") String id);
}
