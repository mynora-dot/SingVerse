SELECT * FROM playback_history WHERE activity_prepared=0 ORDER BY started_at,id LIMIT @{limit}
