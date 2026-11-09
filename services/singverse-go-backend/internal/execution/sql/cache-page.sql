SELECT e.id,e.song_id,e.media_type,e.bytes,e.residue_bytes,e.created_at,e.last_access_at,e.hits,e.state,e.target_json,p.last_played_at,p.heat,p.heat_updated_at,p.pinned,p.pin_revision,
   (SELECT MIN(e0.created_at) FROM song_cache_entry e0 WHERE e0.song_id=e.song_id) AS first_cached_at,
   (SELECT COUNT(*) FROM playback_history h WHERE h.song_id=e.song_id AND h.effective_at>=@{since} AND h.effective_at<=@{now} AND h.client_type<>'ADMIN') AS plays_7d,
   (SELECT COUNT(*) FROM playback_attempt a WHERE a.song_id=e.song_id AND a.started_playing_at IS NOT NULL AND a.lease_until>@{now} AND a.state IN ('PLAYING','PAUSED','BUFFERING')) AS playing_count,
   (SELECT COUNT(*) FROM song_cache_lease l JOIN song_cache_entry e2 ON e2.id=l.cache_id WHERE e2.song_id=e.song_id AND l.renewed_at>@{now}-120000) AS lease_count
  FROM (SELECT id,song_id,media_type,bytes,residue_bytes,created_at,last_access_at,hits,state,target_json FROM song_cache_entry ORDER BY last_access_at DESC,id LIMIT @{size} OFFSET @{offset}) e
  LEFT JOIN song_playback_profile p ON p.song_id=e.song_id ORDER BY e.last_access_at DESC,e.id
