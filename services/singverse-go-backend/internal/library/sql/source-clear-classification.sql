DELETE FROM classification_song WHERE song_id IN(SELECT id FROM media_song WHERE source_id=?)
