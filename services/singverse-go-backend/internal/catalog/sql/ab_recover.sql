UPDATE artist_artwork_item SET status='PENDING',message='服务已恢复，等待继续获取',updated_at=? WHERE status='PROCESSING';
