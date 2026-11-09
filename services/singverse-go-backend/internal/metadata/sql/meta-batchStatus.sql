UPDATE metadata_batch SET status = @{status}, updated_at = @{now} WHERE id = @{id} AND status = @{expected}
