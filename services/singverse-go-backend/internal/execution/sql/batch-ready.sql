UPDATE audio_batch SET state='PREVIEW',updated_at=@{now} WHERE id=@{id} AND state='PREPARING' AND prepared_count=selection_total
