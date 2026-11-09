INSERT INTO media_transcode(id,execution_id,pipeline_version,song_id,owner_id,purpose,state,fingerprint,node_origin,input_json,target_json,result_json,error,renewed_at,created_at,updated_at)
 VALUES(@{id},@{id},'2',@{song_id},@{owner_id},@{purpose},@{state},@{fingerprint},@{node_origin},@{input_json},@{target_json},'{}','',@{now},@{now},@{now})
