SELECT t.*,
    CASE WHEN t.type='LX_DOWNLOAD' THEN CASE WHEN EXISTS(SELECT 1 FROM lx_download_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.song_id IS NOT NULL THEN CASE WHEN EXISTS(SELECT 1 FROM media_song s WHERE s.id=t.song_id) THEN 1 ELSE 0 END
      WHEN t.source_id IS NOT NULL THEN CASE WHEN EXISTS(SELECT 1 FROM media_source s WHERE s.id=t.source_id) THEN 1 ELSE 0 END
      WHEN t.type='AUDIO_BATCH' THEN CASE WHEN EXISTS(SELECT 1 FROM audio_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='METADATA' THEN CASE WHEN EXISTS(SELECT 1 FROM metadata_batch b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='CLASSIFICATION' THEN CASE WHEN EXISTS(SELECT 1 FROM classification_task b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      WHEN t.type='CACHE_CLEANUP' THEN CASE WHEN EXISTS(SELECT 1 FROM song_cache_cleanup_job b WHERE b.id=t.business_id) THEN 1 ELSE 0 END
      ELSE 1 END AS subject_available
   FROM background_task t
    WHERE EXISTS(SELECT 1 FROM background_task_relation r WHERE r.batch_id=@{taskId} AND r.task_id=t.id)
     AND (@{type} IS NULL OR (t.type=@{type}))  AND (@{status} IS NULL OR (t.status=@{status}))

    ORDER BY
    CASE t.status WHEN 'RUNNING' THEN 0 WHEN 'CANCELLING' THEN 1 WHEN 'QUEUED' THEN 2
      WHEN 'PAUSED' THEN 3 WHEN 'AWAITING_CONFIRMATION' THEN 4 ELSE 5 END
  ,t.created_at DESC,t.id DESC LIMIT @{size} OFFSET @{offset}
