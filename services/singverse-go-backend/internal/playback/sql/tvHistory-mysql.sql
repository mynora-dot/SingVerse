WITH eligible AS (
  SELECT id,song_id,title,artists_json,version_label,source_name,status,started_at,ended_at,played_ms,duration_ms,
    CASE WHEN played_ms >= 30000 OR (duration_ms > 0 AND duration_ms < 30000
      AND status='COMPLETED' AND played_ms >= duration_ms) THEN 1 ELSE 0 END AS effective_play
  FROM playback_history
  WHERE client_type='TV' AND session_id IN (SELECT session_id COLLATE utf8mb4_0900_ai_ci FROM JSON_TABLE(@{sessionIds}, '$[*]' COLUMNS (session_id VARCHAR(64) PATH '$')) AS device_sessions) AND started_at <= @{until}
    AND status IN ('COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')
    AND (@{availableOnly}=0 OR song_id IN (SELECT song_id COLLATE utf8mb4_0900_ai_ci FROM JSON_TABLE(@{songIds}, '$[*]' COLUMNS (song_id VARCHAR(64) PATH '$')) AS visible_songs))
)
SELECT * FROM eligible ORDER BY started_at DESC,id DESC LIMIT @{size} OFFSET @{offset}
