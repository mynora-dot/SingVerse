DELETE FROM media_song WHERE source_id=@{sourceId} AND id IN(SELECT song_id FROM webdav_resource WHERE source_id=@{sourceId} AND archived_at IS NOT NULL)
