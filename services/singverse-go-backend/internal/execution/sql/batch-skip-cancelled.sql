UPDATE audio_batch_item SET state='CANCELLED',updated_at=@{now} WHERE batch_id=@{id} AND state='PENDING' AND job_id IS NULL
