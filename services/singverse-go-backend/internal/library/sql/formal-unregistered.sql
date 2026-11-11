SELECT song.id FROM media_song song JOIN media_source source ON source.id=song.source_id LEFT JOIN metadata_document doc ON doc.id=song.id WHERE doc.id IS NULL ORDER BY song.id LIMIT 100
