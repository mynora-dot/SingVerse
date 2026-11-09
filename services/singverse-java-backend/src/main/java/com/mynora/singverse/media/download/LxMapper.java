package com.mynora.singverse.media.download;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** All persistence SQL lives in mappers/download/LxMapper.xml. */
@Mapper
public interface LxMapper {
    Map<String,Object> settings();
    int lockSettings();
    int bind(Map<String,Object> args);
    int activate(Map<String,Object> args);
    List<Map<String,Object>> scripts();
    Map<String,Object> script(Map<String,Object> args);
    Map<String,Object> sameScript(Map<String,Object> args);
    int insertScript(Map<String,Object> args);
    int deleteScript(Map<String,Object> args);
    List<String> secrets();
    int activeSource(Map<String,Object> args);
    int activeScript(Map<String,Object> args);
    Map<String,Object> track(Map<String,Object> args);
    int deleteTrack(Map<String,Object> args);
    int insertTrack(Map<String,Object> args);
    int pruneTracks(Map<String,Object> args);
    int insertBatch(Map<String,Object> args);
    int insertItem(Map<String,Object> args);
    Map<String,Object> activeItem(Map<String,Object> args);
    List<Map<String,Object>> batches(Map<String,Object> args);
    long batchCount();
    Map<String,Object> batch(Map<String,Object> args);
    List<Map<String,Object>> items(Map<String,Object> args);
    long itemCount(Map<String,Object> args);
    List<Map<String,Object>> counts(Map<String,Object> args);
    List<Map<String,Object>> queued();
    int claim(Map<String,Object> args);
    Map<String,Object> item(Map<String,Object> args);
    int progress(Map<String,Object> args);
    int updateItem(Map<String,Object> args);
    int updateBatch(Map<String,Object> args);
    int cancel(Map<String,Object> args);
    List<Map<String,Object>> recovering();
    List<Map<String,Object>> transferring();
    int interrupt(Map<String,Object> args);
    List<Map<String,Object>> activeBatches();
    Map<String,Object> asset(Map<String,Object> args);
    List<Map<String,Object>> assetsForTrack(Map<String,Object> args);
    Map<String,Object> songAsset(Map<String,Object> args);
    int deleteAsset(Map<String,Object> args);
    int insertAsset(Map<String,Object> args);
    int insertSong(Map<String,Object> args);
    List<Map<String,Object>> sourceAssets(Map<String,Object> args);
    int activeTrack(Map<String,Object> args);
    List<Map<String,Object>> retryItems(Map<String,Object> args);
    Map<String,Object> qualityOwner(Map<String,Object> args);
    int reserveQuality(Map<String,Object> args);
}
