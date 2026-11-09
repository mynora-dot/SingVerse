UPDATE lx_download_item SET status='CANCELLED',active_key=NULL,resolved_active_key=NULL,message='已取消',updated_at=@{now} WHERE batch_id=@{id} AND status IN ('QUEUED','RESOLVING','DOWNLOADING')
