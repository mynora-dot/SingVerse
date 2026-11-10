UPDATE webdav_metadata_source SET binding=#{binding},access_group=#{group},blocked_until=NULL,blocked_reason=NULL,empty_scans=0,empty_since=NULL WHERE source_id=#{sourceId}
