SELECT id,provider AS subject_name,status AS raw_status,created_at,updated_at,created_at AS started_at,finished_at FROM artist_artwork_batch WHERE id=@{businessId}
