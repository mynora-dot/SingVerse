UPDATE webdav_resource SET state='IMPORTED' WHERE song_id=@{songId} AND result_json IS NULL AND state='PENDING'
