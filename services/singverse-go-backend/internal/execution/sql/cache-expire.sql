DELETE FROM song_cache_lease WHERE renewed_at<=@{since}
