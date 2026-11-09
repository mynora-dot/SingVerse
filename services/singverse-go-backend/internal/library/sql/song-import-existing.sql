SELECT id FROM media_song WHERE id=? UNION SELECT song_id FROM webdav_resource WHERE song_id=? LIMIT 1
