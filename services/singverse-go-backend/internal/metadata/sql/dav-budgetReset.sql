UPDATE webdav_access_budget SET day_key=@{day},files_started=0,bytes_read=0 WHERE access_group=@{group} AND day_key<>@{day}
