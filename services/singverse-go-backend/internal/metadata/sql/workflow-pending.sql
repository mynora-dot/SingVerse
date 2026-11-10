SELECT * FROM media_import_job WHERE state IN ('PARSING','QUEUED','RUNNING','VERIFYING') ORDER BY created_at,id LIMIT 8
