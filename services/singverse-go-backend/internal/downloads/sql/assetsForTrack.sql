SELECT a.*,s.location AS source_path FROM lx_download_asset a JOIN media_source s ON s.id=a.source_id WHERE a.track_key=@{trackKey}
