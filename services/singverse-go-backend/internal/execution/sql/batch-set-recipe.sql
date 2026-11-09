UPDATE audio_batch SET recipe_json=@{recipe} WHERE id=@{id} AND state='PREPARING'
