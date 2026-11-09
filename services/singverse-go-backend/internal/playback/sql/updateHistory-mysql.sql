UPDATE playback_history SET played_ms=@{actual_played_ms},duration_ms=@{duration_ms},
      status=@{history_status},ended_at=@{ended_at},last_activity_at=@{last_activity_at},
      heat_applied=CASE WHEN effective_at IS NULL AND @{effective_at} IS NOT NULL THEN 0 ELSE heat_applied END,
      effective_at=@{effective_at}
    WHERE id=@{history_id}
