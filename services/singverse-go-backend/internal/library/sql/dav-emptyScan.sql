UPDATE webdav_metadata_source SET empty_scans=empty_scans+1,empty_since=COALESCE(empty_since,#{now}),last_empty_scan=#{taskId} WHERE source_id=#{sourceId}
