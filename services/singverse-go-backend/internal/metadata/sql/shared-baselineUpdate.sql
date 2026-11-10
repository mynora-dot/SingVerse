UPDATE media_shared_baseline SET revision=@{revision},snapshot_id=@{snapshotId} WHERE source_id=@{sourceId} AND collection_id=@{collectionId} AND revision<=@{revision}
