SELECT id, batch_id, song_id, fingerprint, title, artists_json, status, candidates_json, error_message, updated_at FROM metadata_item WHERE id = @{id}
