SELECT i.id,i.artist_name AS title,i.status,i.message,i.updated_at AS source_updated_at
FROM artist_artwork_item i LEFT JOIN background_task_item b ON b.task_id=@{taskId} AND b.id=i.id
WHERE i.batch_id=@{businessId} AND i.id > @{cursor}
AND (b.id IS NULL OR CAST(b.status AS BINARY) <> CAST(i.status AS BINARY) OR CAST(b.source_updated_at AS BINARY) <> CAST(i.updated_at AS BINARY))
ORDER BY i.id LIMIT 200
