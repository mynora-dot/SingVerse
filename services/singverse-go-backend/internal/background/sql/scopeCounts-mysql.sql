SELECT state,COUNT(*) AS amount,SUM(uncertain) AS uncertain FROM background_scan_scope WHERE batch_id=@{id} GROUP BY state
