SELECT COUNT(*) FROM background_task t
    WHERE EXISTS(SELECT 1 FROM background_task_relation r WHERE r.batch_id=@{taskId} AND r.task_id=t.id)
     AND (@{type} IS NULL OR (t.type=@{type}))  AND (@{status} IS NULL OR (t.status=@{status}))
