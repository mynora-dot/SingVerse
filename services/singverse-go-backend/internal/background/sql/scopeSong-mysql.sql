SELECT s.id,s.source_id,src.name AS source_name FROM media_song s JOIN media_source src ON src.id=s.source_id WHERE s.id=@{songId}
