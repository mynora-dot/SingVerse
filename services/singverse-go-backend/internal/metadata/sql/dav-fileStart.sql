UPDATE webdav_access_budget SET files_started=files_started+1,next_file_at=@{after} WHERE access_group=@{group} AND (next_file_at IS NULL OR next_file_at<=@{now})
