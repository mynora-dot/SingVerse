SELECT status,COUNT(*) AS amount FROM metadata_item WHERE batch_id=@{businessId} GROUP BY status
