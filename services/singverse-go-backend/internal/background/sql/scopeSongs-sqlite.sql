SELECT s.id,s.source_id,
      CASE WHEN r.song_id IS NOT NULL THEN r.identity_size ELSE s.file_size END AS file_size,
      CASE WHEN r.song_id IS NOT NULL THEN r.identity_modified ELSE s.modified_at END AS modified_at
    FROM media_song s LEFT JOIN webdav_resource r ON r.song_id=s.id AND r.archived_at IS NULL
    WHERE s.source_id=@{sourceId} AND s.id > @{cursor}
     AND (@{scanBusiness} IS NULL OR (EXISTS(SELECT 1 FROM media_scan_song staged WHERE staged.task_id=@{scanBusiness} AND staged.id=s.id)))
    ORDER BY s.id LIMIT 200
