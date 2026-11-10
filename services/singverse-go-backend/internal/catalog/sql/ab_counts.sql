SELECT status,COUNT(*) AS n FROM artist_artwork_item WHERE batch_id=? GROUP BY status;
