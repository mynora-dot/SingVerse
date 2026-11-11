SELECT song_id,snapshot_json,collection_id,revision FROM media_shared_song WHERE song_id>@{after} ORDER BY song_id LIMIT 100
