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

    SELECT COUNT(*) FROM (
      SELECT song_id FROM filtered WHERE client_type <> 'ADMIN' GROUP BY song_id HAVING SUM(effective_play) > 0
    ) ranked
