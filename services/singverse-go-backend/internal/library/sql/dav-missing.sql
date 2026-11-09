UPDATE webdav_resource SET missing_scans=missing_scans+1,missing_since=COALESCE(missing_since,#{now})
 WHERE source_id=#{sourceId} AND archived_at IS NULL AND (last_scan_id IS NULL OR last_scan_id<>#{taskId})
