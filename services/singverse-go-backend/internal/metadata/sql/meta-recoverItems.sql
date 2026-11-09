UPDATE metadata_item SET status = 'PENDING', updated_at = @{now} WHERE status = 'PROCESSING'
