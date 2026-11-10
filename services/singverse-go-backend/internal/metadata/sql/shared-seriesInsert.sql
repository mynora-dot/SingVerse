INSERT INTO media_share_series(source_id,collection_id) SELECT @{sourceId},@{collectionId} WHERE NOT EXISTS(SELECT 1 FROM media_share_series WHERE source_id=@{sourceId})
