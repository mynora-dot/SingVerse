SELECT j.id,j.song_id,j.kind,j.state AS raw_status,j.result_json,j.error,j.created_at,j.updated_at,
          s.result_json AS song_json,s.source_id,src.name AS source_name,
          (SELECT MIN(batch_id) FROM audio_batch_item WHERE job_id=j.id) AS parent_business_id,
          CASE WHEN j.kind='PROBE' AND j.node_origin='' THEN 0 ELSE 1 END AS tracked
        FROM media_audio_job j LEFT JOIN media_song s ON s.id=j.song_id
        LEFT JOIN media_source src ON src.id=s.source_id WHERE j.id=@{businessId}
