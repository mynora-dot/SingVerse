INSERT INTO webdav_metadata_task(id,song_id,source_id,binding,generation,available_at,created_at,`manual`)
 SELECT r.task_id,r.song_id,r.source_id,r.binding,r.generation,@{now},@{now},1 FROM webdav_resource r
 WHERE r.song_id=@{songId} AND r.archived_at IS NULL AND r.state='PENDING'
 AND NOT EXISTS(SELECT 1 FROM webdav_metadata_task t WHERE t.song_id=r.song_id AND t.binding=r.binding AND t.generation=r.generation)
