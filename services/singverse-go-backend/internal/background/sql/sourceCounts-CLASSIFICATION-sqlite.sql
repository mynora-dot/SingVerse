SELECT status,COUNT(*) AS amount FROM classification_item WHERE task_id=@{businessId} GROUP BY status
