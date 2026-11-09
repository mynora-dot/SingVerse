SELECT effective_at FROM playback_history WHERE song_id=@{song} AND effective_at IS NOT NULL
      AND client_type<>'ADMIN' AND (heat_applied=1 OR id=@{current}) ORDER BY effective_at FOR UPDATE
