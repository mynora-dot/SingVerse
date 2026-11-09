SELECT id FROM background_task WHERE type='LOCAL_SCAN' AND status='INTERRUPTED' AND id > @{cursor} ORDER BY id LIMIT 100
