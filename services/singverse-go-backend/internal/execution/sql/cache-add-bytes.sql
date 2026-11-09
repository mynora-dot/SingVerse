UPDATE song_cache_entry SET bytes=bytes+@{bytes},last_access_at=@{now} WHERE id=@{id}
