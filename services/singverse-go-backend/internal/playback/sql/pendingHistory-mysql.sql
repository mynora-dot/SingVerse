SELECT * FROM playback_history WHERE heat_applied=0 AND activity_prepared=1
    ORDER BY effective_at,started_at,id LIMIT @{limit}
