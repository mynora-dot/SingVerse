INSERT INTO webdav_resource(song_id,source_id,binding,relative_path,path_hash,task_id,last_seen_at,last_scan_id,identity_size,identity_modified,file_size,modified_at,etag)
 SELECT s.id,s.source_id,#{binding},s.relative_path,s.path_hash,s.id,#{now},#{taskId},s.file_size,s.modified_at,s.file_size,s.modified_at,s.etag FROM media_scan_song s
 WHERE s.task_id=#{taskId} AND NOT EXISTS(SELECT 1 FROM webdav_resource r WHERE r.song_id=s.id)
