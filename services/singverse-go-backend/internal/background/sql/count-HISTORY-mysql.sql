SELECT COUNT(*) FROM background_task t
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
