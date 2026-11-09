SELECT type,status,COUNT(*) AS amount FROM background_task GROUP BY type,status
