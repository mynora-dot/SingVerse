SELECT id,artist_id,artist_name,status,message FROM artist_artwork_item WHERE batch_id=? ORDER BY ordinal,id LIMIT ? OFFSET ?;
