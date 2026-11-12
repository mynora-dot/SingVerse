SELECT COUNT(*) FROM (
  SELECT song_id FROM playback_history
  WHERE client_type IN ('TV','MOBILE') AND started_at >= @{from} AND started_at <= @{until}
    AND song_id IN (SELECT value FROM json_each(@{songIds}))
  GROUP BY song_id
  HAVING SUM(CASE WHEN played_ms >= 30000 OR (duration_ms > 0 AND duration_ms < 30000
    AND status='COMPLETED' AND played_ms >= duration_ms) THEN 1 ELSE 0 END) > 0
) AS ranked
