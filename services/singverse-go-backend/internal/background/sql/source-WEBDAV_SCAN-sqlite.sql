SELECT t.id,t.source_id,s.name AS subject_name,t.status AS raw_status,t.phase AS stage,
          t.directories,t.entries,t.total_songs,t.matched_songs,t.review_songs,t.skipped_files,
          t.added_songs,t.reused_songs,t.missing_songs,t.removed_songs,t.cancel_requested,t.error,
          t.created_at,t.started_at,t.finished_at
        FROM media_scan_task t LEFT JOIN media_source s ON s.id=t.source_id WHERE t.id=@{businessId}
