UPDATE media_import_entry SET relative_path=@{path},mapped_hash=@{hash} WHERE job_id=@{id} AND ordinal=@{ordinal}
