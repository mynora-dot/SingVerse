SELECT track_key,track_json FROM lx_download_item WHERE batch_id=@{id} AND status IN ('FAILED','INTERRUPTED','CANCELLED') ORDER BY id LIMIT 1000
