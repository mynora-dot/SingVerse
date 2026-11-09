SELECT t.id,t.song_id,t.state AS raw_status,t.result_json,t.error,t.created_at,t.updated_at,
          s.result_json AS song_json,s.source_id,src.name AS source_name
        FROM media_transcode t LEFT JOIN media_song s ON s.id=t.song_id
        LEFT JOIN media_source src ON src.id=s.source_id WHERE t.id=@{businessId} AND t.purpose='OFFLINE'
