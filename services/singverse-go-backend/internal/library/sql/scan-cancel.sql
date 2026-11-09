UPDATE media_scan_task SET cancel_requested=1 WHERE id=? AND source_id=? AND active_source IS NOT NULL AND phase='SCANNING'
