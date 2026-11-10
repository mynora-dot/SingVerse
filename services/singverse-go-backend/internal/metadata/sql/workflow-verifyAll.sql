SELECT ordinal,relative_path,song_id,file_size FROM media_import_entry WHERE job_id=@{id} AND song_id IS NOT NULL AND verification IS NULL ORDER BY relative_path
