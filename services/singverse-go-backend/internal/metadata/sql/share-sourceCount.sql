UPDATE webdav_share_job SET total_count=(SELECT COUNT(*) FROM media_song WHERE location_state NOT IN ('REPLACED','UNBOUND') AND source_id=@{sourceId}) WHERE id=@{id}
