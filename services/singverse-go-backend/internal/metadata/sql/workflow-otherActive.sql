SELECT COUNT(*) AS total FROM media_import_job WHERE source_id=@{sourceId} AND id<>@{id} AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
