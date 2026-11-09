SELECT COUNT(*) FROM song_cache_lease l JOIN song_cache_entry e ON e.id=l.cache_id WHERE e.song_id=@{song} AND l.renewed_at>@{since}
