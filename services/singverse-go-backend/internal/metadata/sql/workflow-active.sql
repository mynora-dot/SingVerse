SELECT COUNT(*) AS total FROM media_import_job WHERE source_id=@{sourceId} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
