UPDATE webdav_metadata_task SET state='RUNNING',attempts=attempts+1 WHERE id=@{id} AND state='QUEUED'
