UPDATE media_transcode SET state='QUEUED',pipeline_version='2',execution_id=@{execution},result_json='{}',error='',updated_at=@{now} WHERE id=@{id} AND state NOT IN ('CANCELLED','DELETING')
