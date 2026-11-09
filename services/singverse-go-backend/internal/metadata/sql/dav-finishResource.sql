UPDATE webdav_resource SET state=@{state},result_json=@{result},tags_json=@{tags},extractor_version=@{version},
 updated_at=@{now},changed=0
 WHERE song_id=@{songId} AND binding=@{binding} AND generation=@{generation} AND archived_at IS NULL
