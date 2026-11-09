UPDATE classification_item SET status='PENDING',error='服务重启，等待管理员继续',updated_at=? WHERE status='PROCESSING';
