SELECT id,created_at,updated_at,state AS raw_status FROM song_cache_cleanup_job

    WHERE id > @{cursor} ORDER BY id LIMIT 100
