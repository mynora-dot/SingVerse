SELECT COUNT(*) FROM media_ingest_request WHERE source_id=? AND state IN ('QUEUED','RUNNING')
