INSERT INTO playback_attempt(id,client_type,owner_session_id,token_hash,song_id,snapshot_json,event_seq,
      actual_played_ms,elapsed_ms,state,started_at,client_started_at,last_event_at,lease_until)
    VALUES(@{id},@{client_type},@{owner_session_id},@{token_hash},@{song_id},@{snapshot_json},
      -1,0,0,'PREPARING',@{started_at},@{client_started_at},@{last_event_at},@{lease_until})
