SELECT document_json FROM metadata_document WHERE entity_type=? AND suppressed=0 AND search_text LIKE ? ESCAPE '!' ORDER BY display_title,id LIMIT ? OFFSET ?
