UPDATE webdav_metadata_task SET deep=CASE WHEN @{deep}=1 THEN 1 ELSE deep END WHERE song_id=@{songId} AND generation=@{generation} AND state='QUEUED'
