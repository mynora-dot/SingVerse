UPDATE media_shared_song SET verification=@{verification},verified_at=@{now} WHERE song_id=@{songId}
