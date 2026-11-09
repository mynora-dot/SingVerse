SELECT DISTINCT batch_id AS id FROM background_scan_scope WHERE current_job_id=@{jobId} AND state IN ('PROBING','FAST')
