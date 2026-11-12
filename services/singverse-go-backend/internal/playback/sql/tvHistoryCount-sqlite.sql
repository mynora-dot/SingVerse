SELECT CASE WHEN @{latest}=1 THEN COUNT(DISTINCT song_id) ELSE COUNT(*) END
FROM playback_history
WHERE client_type='TV' AND session_id IN (SELECT value FROM json_each(@{sessionIds})) AND started_at <= @{until}
  AND status IN ('COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')
  AND (@{availableOnly}=0 OR song_id IN (SELECT value FROM json_each(@{songIds})))
