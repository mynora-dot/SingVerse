UPDATE classification_item SET status='CANCELLED',error='任务已终止，未继续分类',updated_at=? WHERE task_id=? AND status IN ('PENDING','PROCESSING');
