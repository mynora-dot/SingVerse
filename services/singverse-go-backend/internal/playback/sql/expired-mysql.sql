SELECT * FROM playback_attempt WHERE state IN ('PREPARING','PLAYING','PAUSED','BUFFERING','ERROR')
      AND lease_until <= @{now} ORDER BY lease_until,id LIMIT @{limit}
