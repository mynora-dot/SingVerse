UPDATE webdav_share_job SET total_count=(SELECT COUNT(*) FROM media_song WHERE source_id=@{sourceId}) WHERE id=@{id}
