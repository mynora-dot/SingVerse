SELECT id FROM media_import_job WHERE state IN ('SUCCEEDED','FAILED','CANCELLED') AND updated_at<@{before} ORDER BY updated_at LIMIT 100
