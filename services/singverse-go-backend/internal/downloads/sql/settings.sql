SELECT c.*,s.name AS source_name,s.location AS source_path,s.source_type FROM lx_download_settings c LEFT JOIN media_source s ON s.id=c.source_id WHERE c.id=1
