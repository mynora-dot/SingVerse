UPDATE media_transcode SET state='RUNNING',result_json=@{result},error='',updated_at=@{now} WHERE id=@{id} AND purpose='REALTIME' AND state IN ('STARTING','RUNNING')
