SELECT COUNT(*) AS count FROM metadata_item WHERE batch_id=@{batchId} AND (@{status} IS NULL OR status=@{status})
