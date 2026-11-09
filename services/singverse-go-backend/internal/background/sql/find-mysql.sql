SELECT t.*,
    CASE WHEN t.type='LX_DOWNLOAD' THEN CASE WHEN EXISTS(SELECT 1 FROM lx_download_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.song_id IS NOT NULL THEN CASE WHEN EXISTS(SELECT 1 FROM media_song s WHERE s.id=t.song_id) THEN 1 ELSE 0 END
      WHEN t.source_id IS NOT NULL THEN CASE WHEN EXISTS(SELECT 1 FROM media_source s WHERE s.id=t.source_id) THEN 1 ELSE 0 END
      WHEN t.type='AUDIO_BATCH' THEN CASE WHEN EXISTS(SELECT 1 FROM audio_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='METADATA' THEN CASE WHEN EXISTS(SELECT 1 FROM metadata_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='CLASSIFICATION' THEN CASE WHEN EXISTS(SELECT 1 FROM classification_task b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='CACHE_CLEANUP' THEN CASE WHEN EXISTS(SELECT 1 FROM song_cache_cleanup_job b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      ELSE 1 END AS subject_available
   FROM background_task t WHERE t.id=@{id}
