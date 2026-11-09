SELECT e.id,e.song_id,e.bytes,e.residue_bytes,e.created_at,c.first_cached_at,e.last_access_at,e.state,p.last_played_at,p.heat,p.heat_updated_at,p.pinned,p.pin_revision,COALESCE(f.plays_7d,0) AS plays_7d,COALESCE(a.playing_count,0) AS playing_count,COALESCE(l.lease_count,0) AS lease_count
  FROM song_cache_entry e LEFT JOIN song_playback_profile p ON p.song_id=e.song_id
  LEFT JOIN (SELECT song_id,MIN(created_at) AS first_cached_at FROM song_cache_entry GROUP BY song_id) c ON c.song_id=e.song_id
  LEFT JOIN (SELECT song_id,COUNT(*) AS plays_7d FROM playback_history WHERE effective_at>=@{since} AND effective_at<=@{now} AND client_type<>'ADMIN' GROUP BY song_id) f ON f.song_id=e.song_id
  LEFT JOIN (SELECT song_id,COUNT(*) AS playing_count FROM playback_attempt WHERE started_playing_at IS NOT NULL AND lease_until>@{now} AND state IN ('PLAYING','PAUSED','BUFFERING') GROUP BY song_id) a ON a.song_id=e.song_id
  LEFT JOIN (SELECT e2.song_id,COUNT(*) AS lease_count FROM song_cache_lease l2 JOIN song_cache_entry e2 ON e2.id=l2.cache_id WHERE l2.renewed_at>@{now}-120000 GROUP BY e2.song_id) l ON l.song_id=e.song_id
  WHERE e.state='ACTIVE'
