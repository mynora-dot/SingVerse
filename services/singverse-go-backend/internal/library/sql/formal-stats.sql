SELECT entity_type,COUNT(*) AS total FROM metadata_document WHERE suppressed=0 GROUP BY entity_type
