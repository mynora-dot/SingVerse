SELECT COUNT(*) FROM missing_song_feedback WHERE (?='' OR search_text LIKE ? ESCAPE '!') AND (?='ALL' OR status=?)
