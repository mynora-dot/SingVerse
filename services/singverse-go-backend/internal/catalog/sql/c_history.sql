SELECT * FROM classification_history WHERE song_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?;
