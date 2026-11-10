SELECT id,source_id,state,scan_task_id FROM media_ingest_request WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at,id LIMIT 10
