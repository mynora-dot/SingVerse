SELECT COALESCE(t.batch_status,t.status) AS status, COUNT(*) AS amount
FROM background_task t
WHERE t.is_batch=1 AND t.batch_kind <> 'UNCLASSIFIED' AND (t.batch_expires_at IS NULL OR t.batch_expires_at > @{now})
AND COALESCE(t.batch_status,t.status) IN ('QUEUED','RUNNING','PAUSED','CANCELLING','AWAITING_CONFIRMATION')
GROUP BY COALESCE(t.batch_status,t.status)