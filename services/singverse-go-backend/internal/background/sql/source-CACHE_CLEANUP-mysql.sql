SELECT id,mode,state AS raw_status,result_json,created_at,updated_at FROM song_cache_cleanup_job WHERE id=@{businessId}
