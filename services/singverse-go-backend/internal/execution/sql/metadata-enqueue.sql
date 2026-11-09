INSERT INTO media_metadata_file (song_id) SELECT @{song} WHERE NOT EXISTS (SELECT 1 FROM media_metadata_file WHERE song_id = @{song})
