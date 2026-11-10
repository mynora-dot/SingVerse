-- migrate:up
CREATE TABLE media_share_series (
 source_id VARCHAR(36) PRIMARY KEY,
 collection_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE webdav_share_job ADD COLUMN collection_id VARCHAR(36);
ALTER TABLE webdav_share_job ADD COLUMN collection_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE media_shared_song (
 song_id VARCHAR(36) PRIMARY KEY,
 source_id VARCHAR(36) NOT NULL,
 collection_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 snapshot_json TEXT NOT NULL,
 verification VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 verified_at VARCHAR(40),
 expected_size BIGINT,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_shared_source_verification ON media_shared_song(source_id,verification,song_id);
CREATE TABLE media_shared_baseline (
 source_id VARCHAR(36) NOT NULL,
 collection_id VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL,
 snapshot_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(source_id,collection_id)
);
CREATE TABLE media_import_job (
 id VARCHAR(36) PRIMARY KEY,
 source_id VARCHAR(36) NOT NULL,
 binding VARCHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL,
 phase VARCHAR(20) NOT NULL DEFAULT 'PARSING',
 remove_prefix VARCHAR(2048) NOT NULL DEFAULT '',
 add_prefix VARCHAR(2048) NOT NULL DEFAULT '',
 collection_id VARCHAR(64) NOT NULL DEFAULT '',
 collection_revision BIGINT NOT NULL DEFAULT 0,
 snapshot_id VARCHAR(36) NOT NULL DEFAULT '',
 base_id VARCHAR(36) NOT NULL DEFAULT '',
 complete_snapshot INTEGER NOT NULL DEFAULT 0,
 total_count BIGINT NOT NULL DEFAULT 0,
 processed_count BIGINT NOT NULL DEFAULT 0,
 added_count BIGINT NOT NULL DEFAULT 0,
 updated_count BIGINT NOT NULL DEFAULT 0,
 deletion_count BIGINT NOT NULL DEFAULT 0,
 preserved_count BIGINT NOT NULL DEFAULT 0,
 unchanged_count BIGINT NOT NULL DEFAULT 0,
 verified_count BIGINT NOT NULL DEFAULT 0,
 present_count BIGINT NOT NULL DEFAULT 0,
 missing_count BIGINT NOT NULL DEFAULT 0,
 mismatch_count BIGINT NOT NULL DEFAULT 0,
 unreachable_count BIGINT NOT NULL DEFAULT 0,
 error VARCHAR(300),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_media_import_pending ON media_import_job(state,created_at,id);
CREATE TABLE media_import_entry (
 job_id VARCHAR(36) NOT NULL,
 ordinal BIGINT NOT NULL,
 original_path VARCHAR(2048) NOT NULL,
 relative_path VARCHAR(2048) NOT NULL,
 path_hash VARCHAR(64) NOT NULL,
 mapped_hash VARCHAR(64) NOT NULL,
 file_size BIGINT,
 snapshot_json TEXT NOT NULL,
 song_id VARCHAR(36),
 outcome VARCHAR(20),
 differences_json TEXT,
 verification VARCHAR(20),
 verification_error VARCHAR(300),
 PRIMARY KEY(job_id,ordinal),
 UNIQUE(job_id,path_hash)
);
CREATE TABLE media_ingest_request (
 id VARCHAR(36) PRIMARY KEY,
 source_id VARCHAR(36) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
 scan_task_id VARCHAR(36),
 error VARCHAR(300),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_media_ingest_pending ON media_ingest_request(state,created_at,id);
-- migrate:down
DROP TABLE media_ingest_request;
DROP TABLE media_import_entry;
DROP TABLE media_import_job;
DROP TABLE media_shared_baseline;
DROP TABLE media_shared_song;
ALTER TABLE webdav_share_job DROP COLUMN collection_revision;
ALTER TABLE webdav_share_job DROP COLUMN collection_id;
DROP TABLE media_share_series;
