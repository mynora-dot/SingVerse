SELECT COUNT(*) FROM media_metadata_file f JOIN media_song s ON s.id=f.song_id WHERE s.source_id=@{sourceId} AND f.synced_revision=f.revision AND f.error IS NOT NULL
