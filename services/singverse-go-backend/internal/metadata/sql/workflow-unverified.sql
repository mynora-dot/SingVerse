SELECT * FROM media_import_entry WHERE job_id=@{id} AND song_id IS NOT NULL AND verification IS NULL ORDER BY ordinal LIMIT 100
