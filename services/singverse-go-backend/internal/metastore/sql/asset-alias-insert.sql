INSERT INTO metadata_asset_alias(legacy_id,asset_id) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM metadata_asset_alias WHERE legacy_id=?)
