SELECT id,created_at,updated_at,status AS raw_status FROM artist_artwork_batch WHERE id > @{cursor} ORDER BY id LIMIT 100
