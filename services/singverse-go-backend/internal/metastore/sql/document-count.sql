SELECT COUNT(*) FROM metadata_document WHERE entity_type=? AND suppressed=0 AND search_text LIKE ? ESCAPE '!'
