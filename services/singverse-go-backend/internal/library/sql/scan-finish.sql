UPDATE media_scan_task SET status=?,error=?,active_source=NULL,finished_at=? WHERE id=? AND active_source IS NOT NULL
