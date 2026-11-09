SELECT s.* FROM background_scan_scope s
    WHERE s.state IN ('PENDING','PROBING','FAST') AND (s.batch_id > @{batchCursor}
      OR (s.batch_id=@{batchCursor} AND s.song_id > @{songCursor}))
    ORDER BY s.batch_id,s.song_id LIMIT 50
