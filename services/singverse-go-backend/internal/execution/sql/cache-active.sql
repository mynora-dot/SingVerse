SELECT COUNT(*) FROM song_cache_lease WHERE cache_id=@{id} AND renewed_at>@{since}
