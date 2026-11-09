SELECT id,state AS raw_status,selection_total,prepared_count,preparation_error AS error,created_at,updated_at
        FROM audio_batch WHERE id=@{businessId}
