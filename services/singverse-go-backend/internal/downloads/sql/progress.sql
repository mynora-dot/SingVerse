UPDATE lx_download_item SET status='DOWNLOADING',received_bytes=@{received},total_bytes=@{total},updated_at=@{now} WHERE id=@{id} AND status IN ('RESOLVING','DOWNLOADING')
