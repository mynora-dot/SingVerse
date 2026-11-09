UPDATE song_cache_entry SET state='DELETING',delete_token=@{token},delete_retry_at=0,delete_error='' WHERE id=@{id} AND state='ACTIVE'
