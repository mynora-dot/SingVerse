SELECT id,status AS raw_status,mode,skipped_existing,created_at,updated_at
        FROM metadata_batch WHERE id=@{businessId}
