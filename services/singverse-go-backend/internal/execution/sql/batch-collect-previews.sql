DELETE FROM audio_batch WHERE state IN ('PREPARING','PREVIEW','FAILED','CANCELLED') AND created_at < @{before}
