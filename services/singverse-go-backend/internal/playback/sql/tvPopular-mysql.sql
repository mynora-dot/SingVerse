WITH eligible AS (
  SELECT song_id,started_at,status,
    CASE WHEN played_ms >= 30000 OR (duration_ms > 0 AND duration_ms < 30000
      AND status='COMPLETED' AND played_ms >= duration_ms) THEN 1 ELSE 0 END AS effective_play
  FROM playback_history
  WHERE client_type IN ('TV','MOBILE') AND started_at >= @{from} AND started_at <= @{until}
    AND song_id IN (SELECT song_id COLLATE utf8mb4_0900_ai_ci FROM JSON_TABLE(@{songIds}, '$[*]' COLUMNS (song_id VARCHAR(64) PATH '$')) AS visible_songs)
)
SELECT song_id,COUNT(*) AS play_count,SUM(effective_play) AS effective_play_count,
  SUM(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END) AS completed_count,
  MAX(started_at) AS last_played_at
FROM eligible GROUP BY song_id HAVING SUM(effective_play) > 0
ORDER BY effective_play_count DESC,last_played_at DESC,song_id ASC
LIMIT @{size} OFFSET @{offset}
