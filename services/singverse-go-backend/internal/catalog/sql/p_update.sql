UPDATE playlist SET name=?,description=?,song_ids_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?;
