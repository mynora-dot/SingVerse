SELECT * FROM media_import_entry WHERE job_id=@{id} AND outcome IS NULL ORDER BY ordinal LIMIT 100
