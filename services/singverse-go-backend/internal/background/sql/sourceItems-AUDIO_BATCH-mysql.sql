SELECT i.song_id AS id,i.song_id,i.title,i.state AS status,i.error AS message,
          CAST(i.updated_at AS CHAR) AS source_updated_at,j.result_json AS execution,j.kind,
          COALESCE(j.updated_at,0) AS execution_updated_at,j.id AS job_id,j.state AS job_state
        FROM audio_batch_item i LEFT JOIN media_audio_job j ON j.id=i.job_id
        LEFT JOIN background_task_item b ON b.task_id=@{taskId} AND b.id=i.song_id
        WHERE i.batch_id=@{businessId} AND i.song_id > @{cursor}
          AND (b.id IS NULL OR CAST(b.status AS BINARY) <> CAST(i.state AS BINARY)
            OR CAST(b.source_updated_at AS BINARY) <> CAST(CAST(i.updated_at AS CHAR) AS BINARY)
            OR b.execution_updated_at <> COALESCE(j.updated_at,0))
        ORDER BY i.song_id LIMIT 200
