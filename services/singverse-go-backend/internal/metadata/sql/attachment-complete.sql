UPDATE metadata_attachment_task SET status='SAVED',error=NULL,updated_at=@{now} WHERE media_id=@{songId} AND cover_url=@{url}
