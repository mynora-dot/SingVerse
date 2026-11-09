UPDATE audio_batch SET state='QUEUED',updated_at=@{now} WHERE id=@{id} AND state='PREVIEW'
