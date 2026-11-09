SELECT id,source_id,name AS subject_name,status AS raw_status,created_at,updated_at,started_at,finished_at FROM lx_download_batch WHERE id=@{businessId}
