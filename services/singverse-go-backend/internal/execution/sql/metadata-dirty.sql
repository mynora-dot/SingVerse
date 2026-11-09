INSERT INTO media_metadata_file(song_id) VALUES(@{song}) ON CONFLICT(song_id) DO UPDATE SET revision = revision + 1, error = NULL
