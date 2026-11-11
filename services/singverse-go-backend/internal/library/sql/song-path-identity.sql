SELECT id FROM media_song WHERE source_id=? AND path_hash=? UNION SELECT media_id FROM metadata_location WHERE source_id=? AND path_hash=? LIMIT 1
