INSERT INTO media_metadata_file(song_id) VALUES(@{song}) ON DUPLICATE KEY UPDATE revision = revision + 1, error = NULL
