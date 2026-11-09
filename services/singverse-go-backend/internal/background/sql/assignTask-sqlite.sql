UPDATE background_task SET parent_id=@{batchId},is_batch=0 WHERE id=@{taskId}
      AND (parent_id IS NULL OR parent_id=@{batchId})
