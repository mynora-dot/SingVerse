SELECT t.status,COUNT(*) AS amount,MAX(t.finished_at) AS last_finished,MAX(t.expires_at) AS last_expiry,
      SUM(CASE WHEN t.finished_at IS NULL THEN 1 ELSE 0 END) AS missing_finished,
      SUM(CASE WHEN t.statistics_json LIKE '%"resultState":"UNKNOWN"%' THEN 1 ELSE 0 END) AS uncertain
    FROM background_task_relation r JOIN background_task t ON t.id=r.task_id
    WHERE r.batch_id=@{id} GROUP BY t.status
