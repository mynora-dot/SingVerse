SELECT COUNT(*) AS total,
       COALESCE(SUM(CASE WHEN last_scan_error IS NOT NULL AND last_scan_error <> '' THEN 1 ELSE 0 END), 0) AS failed_total,
       COALESCE(SUM(CASE WHEN last_scan_at IS NULL THEN 1 ELSE 0 END), 0) AS never_scanned
FROM media_source