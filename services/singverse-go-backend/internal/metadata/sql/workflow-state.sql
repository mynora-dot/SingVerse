UPDATE media_import_job SET state=@{state},phase=@{phase},error=@{error},updated_at=@{now} WHERE id=@{id}
