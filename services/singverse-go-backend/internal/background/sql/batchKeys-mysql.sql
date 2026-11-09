SELECT id FROM background_task WHERE is_batch=1 AND id > @{cursor} ORDER BY id LIMIT 100
