UPDATE metadata_batch SET status = 'PAUSED', updated_at = @{now} WHERE status = 'RUNNING'
