UPDATE webdav_resource SET changed=CASE WHEN
 COALESCE(file_size,-1)<>COALESCE((SELECT s.file_size FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),-1)
 OR COALESCE(modified_at,'')<>COALESCE((SELECT s.modified_at FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),'')
 OR COALESCE(etag,'')<>COALESCE((SELECT s.etag FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),'') THEN 1 ELSE changed END,
 file_size=(SELECT s.file_size FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),
 modified_at=(SELECT s.modified_at FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),
 etag=(SELECT s.etag FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id),
 last_seen_at=#{now},last_scan_id=#{taskId},missing_since=NULL,missing_scans=0,archived_at=NULL
 WHERE source_id=#{sourceId} AND EXISTS(SELECT 1 FROM media_scan_song s WHERE s.task_id=#{taskId} AND s.id=webdav_resource.song_id)
