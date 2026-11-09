SELECT status,COUNT(*) AS n FROM classification_item WHERE task_id=? GROUP BY status;
