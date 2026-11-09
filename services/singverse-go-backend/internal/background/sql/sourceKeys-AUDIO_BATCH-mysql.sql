SELECT id,created_at,updated_at,state AS raw_status FROM audio_batch

    WHERE id > @{cursor} ORDER BY id LIMIT 100
