UPDATE audio_batch SET state='FAILED',preparation_error=@{error},updated_at=@{now} WHERE id=@{id} AND state='PREPARING'
