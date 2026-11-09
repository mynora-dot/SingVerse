UPDATE media_scan_task SET status='RUNNING',started_at=? WHERE id=? AND status='QUEUED' AND cancel_requested=0
