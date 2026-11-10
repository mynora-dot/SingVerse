UPDATE media_shared_song SET snapshot_json=@{snapshot},revision=@{revision},updated_at=@{now} WHERE song_id=@{songId}
