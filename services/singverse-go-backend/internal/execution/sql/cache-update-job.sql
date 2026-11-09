UPDATE song_cache_cleanup_job SET state=@{state},updated_at=@{now},result_json=@{result} WHERE id=@{id}
