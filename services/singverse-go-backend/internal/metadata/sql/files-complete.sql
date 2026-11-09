UPDATE media_metadata_file SET synced_revision=@{revision},result_json=@{result},source_location=@{location},error=@{error},updated_at=@{now} WHERE song_id=@{songId} AND revision=@{revision}
