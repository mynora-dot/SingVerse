INSERT INTO song_playback_profile(song_id,heat,pinned,pin_revision)
    SELECT @{song},0,0,0 WHERE NOT EXISTS(SELECT 1 FROM song_playback_profile WHERE song_id=@{song})
