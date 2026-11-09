INSERT INTO song_playback_profile(song_id,last_played_at,heat,heat_updated_at,pinned,pin_revision)
VALUES(@{song},NULL,0,NULL,0,0) ON DUPLICATE KEY UPDATE song_id=VALUES(song_id)
