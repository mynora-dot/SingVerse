UPDATE background_scan_scope SET state='SKIPPED',current_job_id=NULL,reason=@{reason},updated_at=@{now}
    WHERE batch_id=@{batchId} AND state IN ('PENDING','PROBING','FAST')
