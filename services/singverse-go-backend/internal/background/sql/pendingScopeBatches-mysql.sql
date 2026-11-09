SELECT DISTINCT batch_id FROM background_scan_scope WHERE state IN ('PENDING','PROBING','FAST') AND batch_id > @{cursor} ORDER BY batch_id LIMIT 100
