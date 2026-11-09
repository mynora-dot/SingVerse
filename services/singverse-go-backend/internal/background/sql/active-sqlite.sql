SELECT id,type,business_id FROM background_task WHERE id > @{cursor} AND batch_kind='OPERATION'
      AND status IN ('QUEUED','RUNNING','PAUSED','CANCELLING','AWAITING_CONFIRMATION')
    ORDER BY id LIMIT 100
