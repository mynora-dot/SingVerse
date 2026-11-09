UPDATE media_scan_task SET status='INTERRUPTED',active_source=NULL,error='服务已重启，扫描中断；请手动重新扫描',finished_at=? WHERE active_source IS NOT NULL
