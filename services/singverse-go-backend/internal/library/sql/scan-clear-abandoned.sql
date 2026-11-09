DELETE FROM media_scan_song WHERE task_id NOT IN(SELECT id FROM media_scan_task WHERE active_source IS NOT NULL)
