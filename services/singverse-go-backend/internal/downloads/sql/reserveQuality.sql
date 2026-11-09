UPDATE lx_download_item SET resolved_quality=@{quality},resolved_active_key=@{key},updated_at=@{now} WHERE id=@{id} AND status='RESOLVING'
