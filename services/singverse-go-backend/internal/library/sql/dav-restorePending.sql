UPDATE webdav_metadata_task SET state='QUEUED',available_at=#{now}
 WHERE source_id=#{sourceId} AND state='SUPERSEDED' AND EXISTS(SELECT 1 FROM webdav_resource r WHERE r.song_id=webdav_metadata_task.song_id AND r.binding=webdav_metadata_task.binding AND r.generation=webdav_metadata_task.generation AND r.archived_at IS NULL AND r.state='PENDING')
