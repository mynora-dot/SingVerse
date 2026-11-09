SELECT status,COUNT(*) AS amount FROM lx_download_item WHERE batch_id=@{businessId} GROUP BY status
