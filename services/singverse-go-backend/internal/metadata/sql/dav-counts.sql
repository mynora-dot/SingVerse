SELECT state,COUNT(*) AS total FROM webdav_resource WHERE source_id=@{sourceId} AND archived_at IS NULL GROUP BY state
