SELECT id, mode, status, providers_json, auto_apply, threshold, skipped_existing, created_at, updated_at FROM metadata_batch WHERE id = @{id}
