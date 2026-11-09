UPDATE background_scan_scope SET state=@{state},current_job_id=NULL,reason=@{reason},uncertain=@{uncertain},updated_at=@{now}
    WHERE current_job_id=@{jobId} AND state IN ('PROBING','FAST')
