SELECT t.id FROM background_task t WHERE CASE WHEN t.is_batch=1 THEN t.batch_expires_at ELSE t.expires_at END <= @{now}

    AND NOT EXISTS(SELECT 1 FROM background_task_relation r JOIN background_task b ON b.id=r.batch_id
      WHERE r.task_id=t.id AND (b.batch_expires_at IS NULL OR b.batch_expires_at > @{now}))
   ORDER BY t.id LIMIT 100
