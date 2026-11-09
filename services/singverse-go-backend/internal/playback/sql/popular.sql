WITH filtered AS (
      SELECT *, CASE WHEN client_type <> 'ADMIN' AND (played_ms >= 30000
        OR (duration_ms > 0 AND duration_ms < 30000 AND status='COMPLETED' AND played_ms >= duration_ms))
        THEN 1 ELSE 0 END AS effective_play
      FROM playback_history
      WHERE started_at >= @{from} AND started_at <= @{until}
      AND (@{songId}='' OR song_id=@{songId})

        AND (@{pattern}='' OR LOWER(title) LIKE @{pattern} ESCAPE '!'
          OR LOWER(artists_json) LIKE @{pattern} ESCAPE '!')

    )
  , ranking_records AS (
      SELECT * FROM filtered WHERE client_type <> 'ADMIN'
    ), grouped AS (
      SELECT song_id,COUNT(*) AS play_count,SUM(effective_play) AS effective_play_count,
        SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) AS completed_count,
        MAX(started_at) AS last_played_at
      FROM ranking_records GROUP BY song_id HAVING SUM(effective_play) > 0
    )
    SELECT h.song_id,h.title,h.artists_json,h.version_label,h.source_name,
      g.play_count,g.effective_play_count,g.completed_count,g.last_played_at
    FROM grouped g JOIN ranking_records h ON h.song_id=g.song_id
    WHERE NOT EXISTS (
      SELECT 1 FROM ranking_records newer WHERE newer.song_id=h.song_id
        AND (newer.started_at > h.started_at OR (newer.started_at=h.started_at AND newer.id > h.id))
    )
    ORDER BY g.effective_play_count DESC,g.last_played_at DESC,h.song_id ASC
    LIMIT @{size} OFFSET @{offset}
