DELETE FROM metadata_asset WHERE id=? AND NOT EXISTS(SELECT 1 FROM metadata_asset_reference r WHERE r.asset_id=metadata_asset.id)
