UPDATE song_cache_lease SET renewed_at=@{now} WHERE session_id=@{session}
