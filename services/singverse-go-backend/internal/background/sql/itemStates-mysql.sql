SELECT status,COUNT(*) AS amount FROM background_task_item WHERE task_id=@{taskId} GROUP BY status ORDER BY status
