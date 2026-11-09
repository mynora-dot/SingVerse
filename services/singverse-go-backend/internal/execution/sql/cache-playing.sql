SELECT COUNT(*) FROM playback_attempt WHERE song_id=@{song} AND started_playing_at IS NOT NULL AND lease_until>@{now} AND state IN ('PLAYING','PAUSED','BUFFERING')
