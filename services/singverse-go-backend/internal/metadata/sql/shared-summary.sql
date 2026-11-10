SELECT verification,COUNT(*) AS total FROM media_shared_song WHERE source_id=@{sourceId} GROUP BY verification
