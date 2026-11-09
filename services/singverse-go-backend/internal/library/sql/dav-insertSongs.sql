INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,selected_rule_id,result_json,needs_review,embedded_tags_json)
 SELECT s.id,s.source_id,s.relative_path,s.path_hash,s.file_size,s.modified_at,s.scanned_at,s.parser_profile_id,s.parser_revision,s.selected_rule_id,s.result_json,s.needs_review,r.tags_json
 FROM media_scan_song s JOIN webdav_resource r ON r.song_id=s.id
 WHERE s.task_id=#{taskId} AND NOT EXISTS(SELECT 1 FROM media_song old WHERE old.id=s.id)
