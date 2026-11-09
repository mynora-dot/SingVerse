INSERT INTO background_task_relation(batch_id,task_id,created_at) VALUES(@{batchId},@{taskId},@{now})
    ON CONFLICT(batch_id,task_id) DO NOTHING
