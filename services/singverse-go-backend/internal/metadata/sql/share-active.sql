SELECT id FROM webdav_share_job WHERE (@{sourceId} IS NULL OR source_id=@{sourceId}) AND state IN ('WAITING','QUEUED','RUNNING')
