SELECT path,path_hash,depth FROM media_scan_directory WHERE task_id=? AND processed=0 LIMIT 1
