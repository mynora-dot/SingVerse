UPDATE background_task SET scope_sealed=1 WHERE batch_kind='AUTOMATIC' AND scope_sealed=0
     AND (@{round} IS NULL OR (business_id LIKE @{round} ESCAPE '!'))
