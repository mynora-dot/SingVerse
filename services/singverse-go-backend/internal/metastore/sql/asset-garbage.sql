SELECT id,suffix FROM metadata_asset WHERE unreferenced_at<? AND NOT EXISTS(SELECT 1 FROM metadata_asset_reference r WHERE r.asset_id=metadata_asset.id) LIMIT 250
