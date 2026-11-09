SELECT * FROM metadata_item WHERE batch_id=@{batchId} AND (@{status} IS NULL OR status=@{status}) ORDER BY id LIMIT @{limit} OFFSET @{offset}
