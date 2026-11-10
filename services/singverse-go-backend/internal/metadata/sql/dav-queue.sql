INSERT INTO webdav_metadata_task(id,song_id,source_id,binding,generation,available_at,created_at)
 SELECT r.task_id,r.song_id,r.source_id,r.binding,r.generation,@{now},@{now} FROM webdav_resource r
 JOIN webdav_metadata_source c ON c.source_id=r.source_id AND c.binding=r.binding
 WHERE c.enabled=1 AND r.source_id=@{sourceId} AND r.archived_at IS NULL AND r.state='PENDING' AND NOT EXISTS(SELECT 1 FROM media_import_job j WHERE j.source_id=r.source_id AND j.state IN ('PARSING','PREVIEW','QUEUED','RUNNING'))
 AND NOT EXISTS(SELECT 1 FROM webdav_metadata_task t WHERE t.song_id=r.song_id AND t.binding=r.binding AND t.generation=r.generation)
