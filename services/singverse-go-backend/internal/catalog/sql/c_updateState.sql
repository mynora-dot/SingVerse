UPDATE classification_song SET revision=revision+1,state_json=? WHERE song_id=? AND revision=?;
