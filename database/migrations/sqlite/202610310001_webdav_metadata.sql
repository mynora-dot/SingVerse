-- migrate:up
CREATE TABLE webdav_metadata_source (
 source_id VARCHAR(36) PRIMARY KEY, binding VARCHAR(64) NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0,
 access_group VARCHAR(64) NOT NULL, private_origins TEXT NOT NULL,
 blocked_until VARCHAR(30), blocked_reason VARCHAR(100), empty_scans INTEGER NOT NULL DEFAULT 0,
 empty_since VARCHAR(30), last_empty_scan VARCHAR(36)
);
CREATE TABLE webdav_resource (
 song_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, binding VARCHAR(64) NOT NULL,
 relative_path VARCHAR(2048) NOT NULL, path_hash VARCHAR(64) NOT NULL, task_id VARCHAR(36) NOT NULL, generation BIGINT NOT NULL DEFAULT 1,
 last_seen_at VARCHAR(30) NOT NULL, last_scan_id VARCHAR(36), missing_since VARCHAR(30),
 missing_scans INTEGER NOT NULL DEFAULT 0, archived_at VARCHAR(30),
 identity_size BIGINT, identity_modified VARCHAR(30), file_size BIGINT, modified_at VARCHAR(30), etag VARCHAR(512), changed INTEGER NOT NULL DEFAULT 0,
 state VARCHAR(30) NOT NULL DEFAULT 'PENDING', result_json TEXT, tags_json TEXT,
 extractor_version VARCHAR(40), updated_at VARCHAR(30), bytes_read BIGINT NOT NULL DEFAULT 0,
 request_count INTEGER NOT NULL DEFAULT 0, elapsed_ms BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_webdav_resource_source ON webdav_resource(source_id, archived_at, song_id);
CREATE TABLE webdav_metadata_task (
 id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, source_id VARCHAR(36) NOT NULL,
 binding VARCHAR(64) NOT NULL, generation BIGINT NOT NULL, deep INTEGER NOT NULL DEFAULT 0,
 state VARCHAR(30) NOT NULL DEFAULT 'QUEUED', attempts INTEGER NOT NULL DEFAULT 0,
 available_at VARCHAR(30) NOT NULL, created_at VARCHAR(30) NOT NULL, error VARCHAR(100),
 UNIQUE(song_id, binding, generation)
);
CREATE INDEX idx_webdav_metadata_pending ON webdav_metadata_task(state, available_at);
CREATE TABLE webdav_access_budget (
 access_group VARCHAR(64) PRIMARY KEY, day_key VARCHAR(10) NOT NULL, files_started INTEGER NOT NULL DEFAULT 0,
 bytes_read BIGINT NOT NULL DEFAULT 0, next_request_at VARCHAR(30), next_file_at VARCHAR(30),
 blocked_until VARCHAR(30)
);
ALTER TABLE media_scan_song ADD COLUMN etag VARCHAR(512);
ALTER TABLE media_scan_task ADD COLUMN added_songs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_scan_task ADD COLUMN reused_songs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_scan_task ADD COLUMN missing_songs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_scan_task ADD COLUMN removed_songs INTEGER NOT NULL DEFAULT 0;

-- migrate:down
ALTER TABLE media_scan_task DROP COLUMN removed_songs;
ALTER TABLE media_scan_task DROP COLUMN missing_songs;
ALTER TABLE media_scan_task DROP COLUMN reused_songs;
ALTER TABLE media_scan_task DROP COLUMN added_songs;
ALTER TABLE media_scan_song DROP COLUMN etag;
DROP TABLE webdav_metadata_task;
DROP TABLE webdav_resource;
DROP TABLE webdav_metadata_source;
DROP TABLE webdav_access_budget;
