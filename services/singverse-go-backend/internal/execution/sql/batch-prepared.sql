UPDATE audio_batch SET prepared_count=prepared_count+@{amount},updated_at=@{now} WHERE id=@{id} AND state='PREPARING'
