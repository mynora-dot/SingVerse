UPDATE metadata_asset SET unreferenced_at=? WHERE id=? AND unreferenced_at IS NULL AND NOT EXISTS(SELECT 1 FROM metadata_asset_reference r WHERE r.asset_id=metadata_asset.id)
