SELECT song_id FROM audio_batch_item WHERE batch_id=@{id} AND state='FAILED' ORDER BY song_id
