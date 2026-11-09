INSERT INTO song_playback_profile(song_id,heat,pinned,pin_revision)
VALUES(@{song},0,0,0)
ON DUPLICATE KEY UPDATE song_id=song_playback_profile.song_id
