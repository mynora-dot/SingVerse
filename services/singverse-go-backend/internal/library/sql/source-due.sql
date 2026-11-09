SELECT id,scan_cron,next_scan_at,source_type FROM media_source WHERE scan_enabled=1 AND next_scan_at<=? ORDER BY next_scan_at,id LIMIT 20
