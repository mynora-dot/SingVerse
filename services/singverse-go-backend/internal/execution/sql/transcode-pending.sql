SELECT * FROM media_transcode WHERE state IN ('QUEUED','STARTING','RUNNING') ORDER BY created_at LIMIT 100
