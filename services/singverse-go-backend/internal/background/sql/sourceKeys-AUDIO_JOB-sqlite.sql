SELECT id,created_at,updated_at,state AS raw_status FROM media_audio_job

    WHERE id > @{cursor} ORDER BY id LIMIT 100
