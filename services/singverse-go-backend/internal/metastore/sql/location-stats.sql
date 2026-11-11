SELECT state,COUNT(*) AS total FROM metadata_location WHERE source_id=? GROUP BY state
