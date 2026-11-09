UPDATE song_cache_entry SET hits=hits+1,last_access_at=@{now} WHERE id=@{id}
