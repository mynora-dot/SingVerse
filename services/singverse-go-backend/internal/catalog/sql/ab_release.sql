UPDATE artist_artwork_item SET status='PENDING',message='结果保存暂未完成，等待重试',updated_at=? WHERE id=? AND status='PROCESSING' AND generation=?;
