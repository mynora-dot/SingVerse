SELECT id FROM metadata_batch WHERE status IN ('RUNNING', 'PAUSED') ORDER BY created_at DESC, id LIMIT 1
