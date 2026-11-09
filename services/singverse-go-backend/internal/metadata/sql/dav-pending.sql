SELECT t.*,r.relative_path,r.file_size,c.access_group,c.private_origins,c.paused,c.enabled FROM webdav_metadata_task t
 JOIN webdav_resource r ON r.song_id=t.song_id AND r.binding=t.binding AND r.generation=t.generation AND r.archived_at IS NULL
 JOIN webdav_metadata_source c ON c.source_id=t.source_id AND c.binding=t.binding
 WHERE t.state='QUEUED' AND t.available_at<=@{now} AND (c.enabled=1 OR t.`manual`=1) AND c.paused=0
 AND (c.blocked_until IS NULL OR c.blocked_until<=@{now})
 AND NOT EXISTS(SELECT 1 FROM webdav_access_budget b WHERE b.access_group=c.access_group
  AND ((b.blocked_until IS NOT NULL AND b.blocked_until>@{now})
   OR (b.next_file_at IS NOT NULL AND b.next_file_at>@{now})))
 ORDER BY t.`manual` DESC,t.available_at,t.created_at,t.id LIMIT 32
