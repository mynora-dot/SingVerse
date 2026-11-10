DELETE FROM media_import_entry WHERE job_id IN (SELECT id FROM media_import_job WHERE source_id=?)
