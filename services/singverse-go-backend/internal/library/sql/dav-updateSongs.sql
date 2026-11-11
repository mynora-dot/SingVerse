UPDATE media_song SET location_state='PRESENT',file_size=(SELECT s.file_size FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
modified_at=(SELECT s.modified_at FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
scanned_at=(SELECT s.scanned_at FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
parser_profile_id=(SELECT s.parser_profile_id FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
parser_revision=(SELECT s.parser_revision FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
selected_rule_id=(SELECT s.selected_rule_id FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
result_json=(SELECT s.result_json FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id),
needs_review=(SELECT s.needs_review FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id) WHERE source_id=#{sourceId} AND EXISTS(SELECT 1 FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=media_song.id)
