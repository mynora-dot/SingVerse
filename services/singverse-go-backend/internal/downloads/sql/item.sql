SELECT i.*,b.source_id,b.source_path,b.script_id,b.status AS batch_status FROM lx_download_item i JOIN lx_download_batch b ON b.id=i.batch_id WHERE i.id=@{id}
