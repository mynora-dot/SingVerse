SELECT id,created_at,updated_at,state AS raw_status FROM media_transcode WHERE purpose='OFFLINE' AND

     id > @{cursor} ORDER BY id LIMIT 100
