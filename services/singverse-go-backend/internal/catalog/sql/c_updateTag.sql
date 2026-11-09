UPDATE classification_tag SET name=?,aliases_json=?,active=?,revision=revision+1 WHERE id=? AND revision=?;
