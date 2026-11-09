INSERT INTO lx_download_batch(id,name,source_id,source_path,script_id,quality,status,created_at,updated_at) VALUES(@{id},@{name},@{sourceId},@{path},@{scriptId},@{quality},'QUEUED',@{now},@{now})
