SELECT status, COUNT(*) AS amount FROM metadata_item WHERE batch_id = @{batchId} GROUP BY status
