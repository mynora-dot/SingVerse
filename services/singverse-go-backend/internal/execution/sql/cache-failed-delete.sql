UPDATE song_cache_entry SET delete_retry_at=@{retry},delete_error=@{error} WHERE id=@{id} AND state='DELETING'
