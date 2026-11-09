SELECT COUNT(*) FROM lx_download_batch WHERE source_id=@{sourceId} AND status IN ('QUEUED','RUNNING','CANCELLING')
