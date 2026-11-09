UPDATE media_transcode SET state='CANCELLED',updated_at=@{now} WHERE id=@{id} AND purpose='REALTIME' AND state IN ('STARTING','RUNNING') AND renewed_at<=@{since}
