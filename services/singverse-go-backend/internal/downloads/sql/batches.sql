SELECT * FROM lx_download_batch ORDER BY CASE WHEN status IN ('RUNNING','CANCELLING') THEN 0 WHEN status='QUEUED' THEN 1 ELSE 2 END,created_at DESC,id DESC LIMIT @{size} OFFSET @{offset}
