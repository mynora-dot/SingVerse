UPDATE media_metadata_file SET revision = revision + 1, error = NULL WHERE song_id = @{song}
