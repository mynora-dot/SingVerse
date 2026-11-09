SELECT * FROM background_task_item WHERE task_id=@{taskId} AND (@{status} IS NULL OR (status=@{status}))  ORDER BY CASE
      WHEN status IN ('PROBING','PROCESSING') AND execution_json LIKE '%"jobState":"RUNNING"%' THEN 0
      WHEN status IN ('PROBING','PROCESSING') AND execution_json LIKE '%"jobState":"QUEUED"%' THEN 2
      WHEN status IN ('RUNNING','PROBING','PROCESSING') THEN 0 WHEN status='CANCELLING' THEN 1
      WHEN status IN ('PENDING','QUEUED') THEN 2 WHEN status='PAUSED' THEN 3 ELSE 5 END,id DESC LIMIT @{size} OFFSET @{offset}
