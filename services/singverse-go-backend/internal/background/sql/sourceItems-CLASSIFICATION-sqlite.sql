SELECT i.id,i.song_id,i.input_json,i.status,i.error AS message,CAST(i.updated_at AS CHAR) AS source_updated_at
        FROM classification_item i LEFT JOIN background_task_item b ON b.task_id=@{taskId} AND b.id=i.id
        WHERE i.task_id=@{businessId} AND i.id > @{cursor}
    AND (b.id IS NULL OR b.status <> i.status OR b.source_updated_at <> CAST(i.updated_at AS CHAR))

        ORDER BY i.id LIMIT 200
