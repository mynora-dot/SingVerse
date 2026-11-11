SELECT song.id,song.source_id,source.name AS source_name,song.relative_path,song.path_hash,
 song.file_size,song.modified_at,song.scanned_at,song.parser_profile_id,song.parser_revision,
 song.selected_rule_id,song.result_json,song.embedded_tags_json,
 meta.track_json AS metadata_track,meta.fields_json AS metadata_fields,
 meta.applied_at AS metadata_applied,meta.origins_json AS metadata_origins,
 meta.fingerprint AS metadata_fingerprint,
 cls.state_json AS classification_state,cls.revision AS classification_revision,
 shared.snapshot_json AS shared_snapshot,shared.verification AS media_verification,remote.result_json AS remote_result,remote.song_id AS remote_song_id,
 remote.identity_size,remote.identity_modified,remote.etag,
 artist_override.source_id AS override_source_id,artist_override.artists_json AS override_artists,
 download.track_json AS download_track,download.created_at AS download_created_at, song.location_state,
 formal_song.document_json AS formal_song, formal_variant.document_json AS formal_variant,formal_media.document_json AS formal_media
 FROM media_song song JOIN media_source source ON source.id=song.source_id
 LEFT JOIN song_metadata meta ON meta.song_id=song.id
 LEFT JOIN classification_song cls ON cls.song_id=song.id
 LEFT JOIN webdav_resource remote ON remote.song_id=song.id AND remote.archived_at IS NULL LEFT JOIN song_artist_override artist_override ON artist_override.source_id=song.source_id AND artist_override.path_key=song.path_hash LEFT JOIN lx_download_asset download ON download.song_id=song.id AND download.file_size=song.file_size AND download.modified_at=song.modified_at
 LEFT JOIN media_shared_song shared ON shared.song_id=song.id
 LEFT JOIN metadata_document formal_media ON formal_media.id=song.id
 LEFT JOIN metadata_document formal_variant ON formal_variant.id=formal_media.parent_id
 LEFT JOIN metadata_document formal_song ON formal_song.id=formal_variant.parent_id
