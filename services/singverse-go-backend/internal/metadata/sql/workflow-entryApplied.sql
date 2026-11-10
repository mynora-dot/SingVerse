UPDATE media_import_entry SET song_id=@{songId},outcome=@{outcome},differences_json=@{differences} WHERE job_id=@{id} AND ordinal=@{ordinal}
