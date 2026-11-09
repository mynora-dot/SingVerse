INSERT INTO playback_history(id,song_id,title,artists_json,version_label,source_name,client_type,
      device_name,requested_by,session_id,attempt_id,started_at,played_ms,duration_ms,status,effective_at,heat_applied,activity_prepared,last_activity_at)
    VALUES(@{history_id},@{song_id},@{title},@{artists_json},@{version_label},@{source_name},
      @{client_type},@{device_name},@{requested_by},@{owner_session_id},@{id},
      @{started_playing_at},0,@{duration_ms},'PLAYING',NULL,0,1,@{started_playing_at})
