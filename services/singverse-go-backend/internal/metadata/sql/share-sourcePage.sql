SELECT id,relative_path,file_size,result_json FROM media_song WHERE location_state NOT IN ('REPLACED','UNBOUND') AND source_id=@{sourceId} AND id>@{cursor} ORDER BY id LIMIT 100
