DELETE FROM artist_credit_binding WHERE song_id IN(SELECT song_id FROM webdav_resource WHERE source_id=@{sourceId})
