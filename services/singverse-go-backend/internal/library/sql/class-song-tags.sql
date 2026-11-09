SELECT t.id,t.dimension,t.name FROM classification_assignment a JOIN classification_tag t ON t.id=a.tag_id AND t.active=1 WHERE a.song_id=? ORDER BY t.dimension,t.id
