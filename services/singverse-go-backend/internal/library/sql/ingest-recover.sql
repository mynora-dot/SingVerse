UPDATE media_ingest_request SET state='QUEUED' WHERE state='RUNNING' AND scan_task_id IS NULL
