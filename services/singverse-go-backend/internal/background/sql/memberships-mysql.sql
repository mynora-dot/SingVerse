SELECT b.id,b.name FROM background_task b JOIN background_task_relation r ON b.id=r.batch_id
    WHERE r.task_id=@{id} AND (b.batch_expires_at IS NULL OR b.batch_expires_at > @{now})
    ORDER BY b.created_at DESC,b.id DESC
