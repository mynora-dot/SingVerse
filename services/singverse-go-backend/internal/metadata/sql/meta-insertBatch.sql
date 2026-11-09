INSERT INTO metadata_batch (id, mode, status, providers_json, auto_apply, threshold, skipped_existing, created_at, updated_at)
    VALUES (@{id}, @{mode}, 'RUNNING', @{providers}, @{autoApply}, @{threshold}, @{skipped}, @{now}, @{now})
