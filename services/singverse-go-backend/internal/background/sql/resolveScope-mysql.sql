UPDATE background_scan_scope SET state=@{state},current_job_id=@{jobId},reason=@{reason},uncertain=@{uncertain},updated_at=@{now}
    WHERE batch_id=@{batchId} AND song_id=@{songId} AND fingerprint=@{fingerprint} AND state IN ('PENDING','PROBING','FAST')
