UPDATE webdav_resource SET archived_at=@{now},state=CASE WHEN state='RUNNING' THEN 'PENDING' ELSE state END WHERE source_id=@{sourceId} AND archived_at IS NULL
 AND missing_scans>=2 AND missing_since<=@{missingBefore}
