SELECT i.state AS status,COUNT(*) AS amount,
          SUM(CASE WHEN i.state IN ('PROBING','PROCESSING') AND j.state='RUNNING' THEN 1 ELSE 0 END) AS executing
        FROM audio_batch_item i LEFT JOIN media_audio_job j ON j.id=i.job_id
        WHERE i.batch_id=@{businessId} GROUP BY i.state
