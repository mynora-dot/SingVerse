SELECT CASE WHEN @{latest}=1 THEN COUNT(DISTINCT song_id) ELSE COUNT(*) END
FROM playback_history
WHERE client_type='TV' AND session_id IN (SELECT session_id COLLATE utf8mb4_0900_ai_ci FROM JSON_TABLE(@{sessionIds}, '$[*]' COLUMNS (session_id VARCHAR(64) PATH '$')) AS device_sessions) AND started_at <= @{until}
  AND status IN ('COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')
  AND (@{availableOnly}=0 OR song_id IN (SELECT song_id COLLATE utf8mb4_0900_ai_ci FROM JSON_TABLE(@{songIds}, '$[*]' COLUMNS (song_id VARCHAR(64) PATH '$')) AS visible_songs))
