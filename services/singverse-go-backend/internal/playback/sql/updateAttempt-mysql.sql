UPDATE playback_attempt SET event_seq=@{event_seq},actual_played_ms=@{actual_played_ms},
      elapsed_ms=@{elapsed_ms},state=@{state},last_event_at=@{last_event_at},lease_until=@{lease_until},
      started_playing_at=@{started_playing_at},history_id=@{history_id},effective_at=@{effective_at},
      duration_ms=@{duration_ms},last_activity_at=@{last_activity_at},client_started_at=@{client_started_at},
      client_last_activity_at=@{client_last_activity_at}
    WHERE id=@{id}
