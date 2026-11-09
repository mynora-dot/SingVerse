SELECT reason FROM background_scan_scope WHERE batch_id=@{id} AND state IN ('PENDING','PROBING','FAST')
      AND reason <> '' ORDER BY updated_at DESC,song_id LIMIT 1
