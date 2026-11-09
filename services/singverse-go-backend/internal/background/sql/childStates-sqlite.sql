SELECT t.status,COUNT(*) AS amount FROM background_task t
    WHERE EXISTS(SELECT 1 FROM background_task_relation r WHERE r.batch_id=@{taskId} AND r.task_id=t.id)
    GROUP BY t.status
