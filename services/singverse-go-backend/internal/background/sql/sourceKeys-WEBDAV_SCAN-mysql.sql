SELECT id,created_at,finished_at AS updated_at,status AS raw_status FROM media_scan_task

    WHERE id > @{cursor} ORDER BY id LIMIT 100
