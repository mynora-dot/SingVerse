WITH eligible AS (
  SELECT id,song_id,title,artists_json,version_label,source_name,status,started_at,ended_at,played_ms,duration_ms,
    CASE WHEN played_ms >= 30000 OR (duration_ms > 0 AND duration_ms < 30000
      AND status='COMPLETED' AND played_ms >= duration_ms) THEN 1 ELSE 0 END AS effective_play
  FROM playback_history
  WHERE client_type='TV' AND session_id IN (SELECT value FROM json_each(@{sessionIds})) AND started_at <= @{until}
    AND status IN ('COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')
    AND (@{availableOnly}=0 OR song_id IN (SELECT value FROM json_each(@{songIds})))
), ranked AS (
  SELECT eligible.*,ROW_NUMBER() OVER (PARTITION BY song_id ORDER BY started_at DESC,id DESC) AS song_rank
  FROM eligible
)
SELECT id,song_id,title,artists_json,version_label,source_name,status,started_at,ended_at,played_ms,duration_ms,effective_play
FROM ranked WHERE song_rank=1
ORDER BY started_at DESC,id DESC LIMIT @{size} OFFSET @{offset}
