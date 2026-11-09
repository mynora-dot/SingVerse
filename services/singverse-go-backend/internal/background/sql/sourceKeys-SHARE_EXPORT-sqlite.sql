SELECT id,created_at,created_at AS updated_at,state AS raw_status FROM webdav_share_job

    WHERE id > @{cursor} ORDER BY id LIMIT 100
