SELECT COUNT(*) FROM classification_item WHERE task_id=? AND (? IS NULL OR status=?);
