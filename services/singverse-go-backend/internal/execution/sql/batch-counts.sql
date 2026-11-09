SELECT state,category,COUNT(*) AS amount FROM audio_batch_item WHERE batch_id=@{id} GROUP BY state,category
