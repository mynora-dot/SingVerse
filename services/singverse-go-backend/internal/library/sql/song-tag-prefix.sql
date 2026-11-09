 AND EXISTS(SELECT 1 FROM classification_assignment a JOIN classification_tag t ON t.id=a.tag_id AND t.active=1 WHERE a.song_id=song.id AND a.dimension=? AND a.tag_id IN (
