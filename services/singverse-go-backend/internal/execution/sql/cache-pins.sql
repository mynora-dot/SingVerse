SELECT p.*,(SELECT COUNT(*) FROM song_cache_entry e WHERE e.song_id=p.song_id) AS cache_count FROM song_playback_profile p WHERE p.pinned=1 ORDER BY p.song_id LIMIT @{size} OFFSET @{offset}
