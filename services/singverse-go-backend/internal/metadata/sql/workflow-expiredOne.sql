SELECT id FROM media_import_job WHERE id=@{id} AND state IN ('SUCCEEDED','FAILED','CANCELLED') AND updated_at<@{before}
