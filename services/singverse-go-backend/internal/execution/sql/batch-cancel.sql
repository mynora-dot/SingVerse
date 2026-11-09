UPDATE audio_batch SET state='CANCELLED',updated_at=@{now} WHERE id=@{id} AND state IN ('PREPARING','PREVIEW','QUEUED')
