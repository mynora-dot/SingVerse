INSERT INTO metadata_item (id, batch_id, song_id, fingerprint, title, artists_json, status, updated_at)
    VALUES (@{id}, @{batchId}, @{songId}, @{fingerprint}, @{title}, @{artists}, 'PENDING', @{now})
