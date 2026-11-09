UPDATE song_playback_profile SET last_played_at=@{lastPlayed},heat=@{heat},heat_updated_at=@{heatAt}
    WHERE song_id=@{song}
