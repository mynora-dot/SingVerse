UPDATE webdav_share_job SET state=@{state},cursor_id=@{cursor},processed_count=@{processed},part_count=@{parts},error=@{error} WHERE id=@{id}
