SELECT mapped_hash FROM media_import_entry WHERE job_id=@{id} GROUP BY mapped_hash HAVING COUNT(*)>1 LIMIT 1
