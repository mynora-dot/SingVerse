SELECT id, name, last_scan_error
FROM media_source
WHERE last_scan_error IS NOT NULL AND last_scan_error <> ''
ORDER BY updated_at DESC, id ASC
LIMIT 5