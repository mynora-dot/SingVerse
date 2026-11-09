UPDATE song_playback_profile SET pinned=@{pinned},pin_revision=pin_revision+1 WHERE song_id=@{song} AND pin_revision=@{revision}
