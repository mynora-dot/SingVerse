SELECT i.id,i.batch_id FROM lx_download_item i JOIN lx_download_batch b ON b.id=i.batch_id WHERE i.status='QUEUED' AND b.status IN ('QUEUED','RUNNING') ORDER BY b.created_at,i.id LIMIT 2
