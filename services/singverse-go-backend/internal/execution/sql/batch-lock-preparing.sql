UPDATE audio_batch SET updated_at=@{now} WHERE id=@{id} AND state='PREPARING'
