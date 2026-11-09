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
    WHERE t.is_batch=1 AND t.batch_kind <> 'UNCLASSIFIED' AND (t.batch_expires_at IS NULL OR t.batch_expires_at > @{now})
    AND COALESCE(t.batch_status,t.status) NOT IN ('QUEUED','RUNNING','PAUSED','CANCELLING','AWAITING_CONFIRMATION')
     AND (@{type} IS NULL OR (t.type=@{type}))
     AND (@{status} IS NULL OR (COALESCE(t.batch_status,t.status)=@{status}))
     AND (@{pattern} IS NULL OR ((LOWER(t.name) LIKE LOWER(@{pattern}) ESCAPE '!'
      OR LOWER(t.subject_name) LIKE LOWER(@{pattern}) ESCAPE '!'
      OR LOWER(t.id) LIKE LOWER(@{pattern}) ESCAPE '!'
      OR LOWER(t.business_id) LIKE LOWER(@{pattern}) ESCAPE '!'
      OR EXISTS(SELECT 1 FROM background_task_relation r JOIN background_task c ON c.id=r.task_id
        WHERE r.batch_id=t.id AND (LOWER(c.name) LIKE LOWER(@{pattern}) ESCAPE '!'
          OR LOWER(c.subject_name) LIKE LOWER(@{pattern}) ESCAPE '!' OR LOWER(c.id) LIKE LOWER(@{pattern}) ESCAPE '!'
          OR LOWER(c.business_id) LIKE LOWER(@{pattern}) ESCAPE '!'))
      OR EXISTS(SELECT 1 FROM background_task_item i WHERE i.task_id=t.id AND
        (LOWER(i.title) LIKE LOWER(@{pattern}) ESCAPE '!' OR LOWER(i.id) LIKE LOWER(@{pattern}) ESCAPE '!'))
      OR EXISTS(SELECT 1 FROM background_scan_scope scope JOIN media_song s ON s.id=scope.song_id
        WHERE scope.batch_id=t.id AND (LOWER(scope.song_id) LIKE LOWER(@{pattern}) ESCAPE '!'
          OR LOWER(JSON_EXTRACT(s.result_json,'$.title')) LIKE LOWER(@{pattern}) ESCAPE '!')))))

    ORDER BY
    CASE CASE WHEN t.is_batch=1 THEN COALESCE(t.batch_status,t.status) ELSE t.status END WHEN 'RUNNING' THEN 0 WHEN 'CANCELLING' THEN 1 WHEN 'QUEUED' THEN 2
      WHEN 'PAUSED' THEN 3 WHEN 'AWAITING_CONFIRMATION' THEN 4 ELSE 5 END
  ,t.created_at DESC,t.id DESC LIMIT @{size} OFFSET @{offset}
