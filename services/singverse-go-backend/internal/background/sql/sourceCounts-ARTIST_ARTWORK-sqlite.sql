SELECT status,COUNT(*) AS amount FROM artist_artwork_item WHERE batch_id=@{businessId} GROUP BY status
