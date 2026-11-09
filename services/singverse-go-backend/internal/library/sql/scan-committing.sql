UPDATE media_scan_task SET phase='COMMITTING' WHERE id=? AND status='RUNNING' AND cancel_requested=0
