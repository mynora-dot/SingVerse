SELECT COUNT(*) FROM background_task_item WHERE task_id=@{taskId} AND (@{status} IS NULL OR (status=@{status}))
