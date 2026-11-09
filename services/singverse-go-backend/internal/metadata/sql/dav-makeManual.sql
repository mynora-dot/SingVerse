UPDATE webdav_metadata_task SET `manual`=1 WHERE song_id=@{songId} AND generation=@{generation} AND state='QUEUED'
