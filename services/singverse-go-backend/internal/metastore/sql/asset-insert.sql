INSERT INTO metadata_asset(id,mime,byte_size,suffix,width,height,created_at,unreferenced_at) SELECT ?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM metadata_asset WHERE id=?)
