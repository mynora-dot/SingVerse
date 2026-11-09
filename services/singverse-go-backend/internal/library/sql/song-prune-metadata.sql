DELETE FROM song_metadata WHERE source_id=? AND song_id NOT IN (SELECT id FROM media_song WHERE source_id=?)
