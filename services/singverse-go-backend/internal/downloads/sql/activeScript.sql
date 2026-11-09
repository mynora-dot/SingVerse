SELECT COUNT(*) FROM lx_download_batch WHERE script_id=@{id} AND status IN ('QUEUED','RUNNING','CANCELLING')
