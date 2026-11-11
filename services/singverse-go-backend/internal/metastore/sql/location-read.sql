SELECT id,media_id,source_id,relative_path,path_hash,local_key,state,observation_json,updated_at FROM metadata_location WHERE media_id=? ORDER BY id
