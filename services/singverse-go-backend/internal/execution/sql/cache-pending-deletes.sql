SELECT * FROM song_cache_entry WHERE state='DELETING' AND delete_retry_at<=@{now} ORDER BY delete_retry_at,id LIMIT 200
