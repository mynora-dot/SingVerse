UPDATE metadata_asset_reference SET expires_at=NULL WHERE owner_type='IMPORT' AND EXISTS(SELECT 1 FROM media_import_job j WHERE j.id=metadata_asset_reference.owner_id)
