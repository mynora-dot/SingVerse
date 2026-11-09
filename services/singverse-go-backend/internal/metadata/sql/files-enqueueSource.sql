INSERT INTO media_metadata_file(song_id) SELECT s.id FROM media_song s WHERE s.source_id=@{sourceId} AND NOT EXISTS(SELECT 1 FROM media_metadata_file f WHERE f.song_id=s.id)
