DELETE FROM metadata_asset_reference WHERE (owner_type='IMPORT' AND owner_id=@{importId}) OR (owner_type='EXPORT' AND owner_id=@{exportId})
