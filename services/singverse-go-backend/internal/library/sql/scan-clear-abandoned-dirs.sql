DELETE FROM media_scan_directory WHERE task_id NOT IN(SELECT id FROM media_scan_task WHERE active_source IS NOT NULL)
