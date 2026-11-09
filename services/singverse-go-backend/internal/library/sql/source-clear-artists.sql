DELETE FROM artist_credit_binding WHERE song_id IN(SELECT id FROM media_song WHERE source_id=?)
