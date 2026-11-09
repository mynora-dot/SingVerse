DELETE FROM song_cache_cleanup_job WHERE created_at<@{before} AND state NOT IN ('QUEUED','RUNNING','CANCELLING')
