INSERT INTO song_metadata (song_id, source_id, fingerprint, track_json, fields_json, origins_json, applied_at)
    VALUES (@{songId}, @{sourceId}, @{fingerprint}, @{track}, @{fields}, @{origins}, @{now})
