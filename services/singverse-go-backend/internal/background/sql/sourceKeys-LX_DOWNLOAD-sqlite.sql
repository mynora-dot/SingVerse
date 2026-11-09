SELECT id,created_at,updated_at,status AS raw_status FROM lx_download_batch
    WHERE id > @{cursor} ORDER BY id LIMIT 100
