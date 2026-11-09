UPDATE lx_download_item SET status='RESOLVING',updated_at=@{now} WHERE id=@{id} AND status='QUEUED'
