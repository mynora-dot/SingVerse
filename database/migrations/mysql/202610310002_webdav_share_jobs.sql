-- migrate:up
CREATE TABLE webdav_share_lock(id INTEGER PRIMARY KEY);
INSERT INTO webdav_share_lock(id) VALUES(1);
CREATE TABLE webdav_share_job (
 id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, binding VARCHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL, base_id VARCHAR(36), cursor_id VARCHAR(36) NOT NULL DEFAULT '',
 total_count BIGINT NOT NULL DEFAULT 0, processed_count BIGINT NOT NULL DEFAULT 0,
 part_count INTEGER NOT NULL DEFAULT 0, error VARCHAR(200), created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_share_job_source ON webdav_share_job(source_id,created_at);
CREATE TABLE webdav_share_entry (
 job_id VARCHAR(36) NOT NULL, song_id VARCHAR(36) NOT NULL, relative_path VARCHAR(2048) NOT NULL,
 file_size BIGINT, result_json TEXT, filename_json TEXT NOT NULL,
 PRIMARY KEY(job_id,song_id)
);
CREATE TABLE webdav_share_asset (job_id VARCHAR(36) NOT NULL, hash VARCHAR(64) NOT NULL, PRIMARY KEY(job_id,hash));
CREATE TABLE webdav_share_receipt (source_id VARCHAR(36) NOT NULL, binding VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL, processed INTEGER NOT NULL DEFAULT 0, imported INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(source_id,binding,hash));

-- migrate:down
DROP TABLE webdav_share_lock;
DROP TABLE webdav_share_receipt;
DROP TABLE webdav_share_asset;
DROP TABLE webdav_share_entry;
DROP TABLE webdav_share_job;
