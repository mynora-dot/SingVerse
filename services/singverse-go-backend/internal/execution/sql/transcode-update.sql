UPDATE media_transcode SET state=@{state},result_json=@{result},error=@{error},updated_at=@{now} WHERE id=@{id} AND state NOT IN ('CANCELLED','DELETING')
