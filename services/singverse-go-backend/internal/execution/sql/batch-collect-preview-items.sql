DELETE FROM audio_batch_item WHERE batch_id IN (SELECT id FROM audio_batch WHERE state IN ('PREPARING','PREVIEW','FAILED','CANCELLED') AND created_at < @{before})
