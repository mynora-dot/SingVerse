-- migrate:up
CREATE TABLE lx_download_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  source_id VARCHAR(36),
  active_script_id VARCHAR(36),
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO lx_download_settings (id) VALUES (1);
CREATE TABLE lx_script (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  version VARCHAR(100) NOT NULL,
  environment VARCHAR(10) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  secret_encrypted LONGTEXT NOT NULL,
  capabilities_json LONGTEXT NOT NULL,
  created_at VARCHAR(30) NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  UNIQUE (content_hash, environment)
);
CREATE TABLE lx_catalog_track (
  id VARCHAR(64) PRIMARY KEY,
  platform VARCHAR(2) NOT NULL,
  remote_id VARCHAR(200) NOT NULL,
  track_json LONGTEXT NOT NULL,
  updated_at VARCHAR(30) NOT NULL
);
CREATE TABLE lx_download_batch (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  source_path VARCHAR(2048) NOT NULL,
  script_id VARCHAR(36) NOT NULL,
  quality VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at VARCHAR(30) NOT NULL,
  updated_at VARCHAR(30) NOT NULL,
  started_at VARCHAR(30),
  finished_at VARCHAR(30)
);
CREATE TABLE lx_download_item (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  track_key VARCHAR(64) NOT NULL,
  track_json LONGTEXT NOT NULL,
  requested_quality VARCHAR(20) NOT NULL,
  resolved_quality VARCHAR(20),
  active_key VARCHAR(200) UNIQUE,
  resolved_active_key VARCHAR(200) UNIQUE,
  status VARCHAR(20) NOT NULL,
  received_bytes BIGINT NOT NULL DEFAULT 0,
  total_bytes BIGINT,
  relative_path VARCHAR(2048),
  song_id VARCHAR(36),
  content_hash VARCHAR(64),
  message VARCHAR(1000) NOT NULL DEFAULT '',
  updated_at VARCHAR(30) NOT NULL,
  UNIQUE (batch_id, track_key)
);
CREATE INDEX idx_lx_item_queue ON lx_download_item (status, updated_at, id);
CREATE INDEX idx_lx_item_batch ON lx_download_item (batch_id, id);
CREATE INDEX idx_lx_batch_history ON lx_download_batch (created_at, id);
CREATE TABLE lx_download_asset (
  id VARCHAR(64) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  track_key VARCHAR(64) NOT NULL,
  quality VARCHAR(20) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  song_id VARCHAR(36) NOT NULL,
  file_size BIGINT NOT NULL,
  modified_at VARCHAR(30) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  track_json LONGTEXT NOT NULL,
  created_at VARCHAR(30) NOT NULL,
  UNIQUE (source_id, track_key, quality)
);
CREATE INDEX idx_lx_asset_song ON lx_download_asset (song_id);

-- migrate:down
DROP TABLE lx_download_asset;
DROP TABLE lx_download_item;
DROP TABLE lx_download_batch;
DROP TABLE lx_catalog_track;
DROP TABLE lx_script;
DROP TABLE lx_download_settings;
