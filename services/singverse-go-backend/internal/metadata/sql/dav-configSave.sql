UPDATE webdav_metadata_source SET enabled=@{enabled},paused=@{paused},access_group=@{group},private_origins=@{origins},blocked_until=NULL,blocked_reason=NULL WHERE source_id=@{sourceId}
