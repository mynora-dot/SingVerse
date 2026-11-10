SELECT id,source_id,state,scan_task_id,error,created_at,updated_at FROM media_ingest_request WHERE source_id=? ORDER BY created_at DESC,id DESC LIMIT 1
