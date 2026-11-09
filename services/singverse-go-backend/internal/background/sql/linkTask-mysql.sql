INSERT INTO background_task_relation(batch_id,task_id,created_at) VALUES(@{batchId},@{taskId},@{now})
    ON DUPLICATE KEY UPDATE task_id=task_id
