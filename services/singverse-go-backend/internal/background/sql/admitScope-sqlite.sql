INSERT INTO background_scan_scope(batch_id,song_id,fingerprint,state,current_job_id,reason,uncertain,updated_at)
    VALUES(@{batchId},@{songId},@{fingerprint},'PENDING',NULL,'等待后续发现',0,@{now})
    ON CONFLICT(batch_id,song_id,fingerprint) DO NOTHING
