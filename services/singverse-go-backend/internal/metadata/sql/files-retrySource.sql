UPDATE media_metadata_file SET revision=revision+1,error=NULL WHERE error IS NOT NULL AND song_id IN(SELECT id FROM media_song WHERE source_id=@{sourceId})
