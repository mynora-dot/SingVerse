SELECT * FROM webdav_share_job WHERE state IN ('WAITING','QUEUED','RUNNING') ORDER BY created_at LIMIT 8
