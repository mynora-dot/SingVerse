UPDATE media_import_job SET remove_prefix=@{removePrefix},add_prefix=@{addPrefix},updated_at=@{now} WHERE id=@{id}
