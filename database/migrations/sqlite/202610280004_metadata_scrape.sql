-- migrate:up
CREATE TABLE metadata_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL DEFAULT 0,
  providers_json TEXT NOT NULL, auto_apply INTEGER NOT NULL DEFAULT 0,
  threshold DOUBLE NOT NULL DEFAULT 0.95, updated_at VARCHAR(30) NOT NULL
);
INSERT INTO metadata_settings (id, providers_json, updated_at) VALUES (1, '["NETEASE"]', '2026-10-28T00:00:00Z');
CREATE TABLE metadata_batch (
  id VARCHAR(36) PRIMARY KEY, mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
  providers_json TEXT NOT NULL, auto_apply INTEGER NOT NULL, threshold DOUBLE NOT NULL,
  skipped_existing INTEGER NOT NULL DEFAULT 0, created_at VARCHAR(30) NOT NULL, updated_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_batch_created ON metadata_batch (created_at, id);
CREATE TABLE metadata_item (
  id VARCHAR(36) PRIMARY KEY, batch_id VARCHAR(36) NOT NULL, song_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL, title VARCHAR(1024), artists_json TEXT NOT NULL,
  status VARCHAR(24) NOT NULL, candidates_json TEXT, error_message VARCHAR(500), updated_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_item_batch ON metadata_item (batch_id, status, id);
CREATE TABLE metadata_candidate (
  id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
  track_json TEXT NOT NULL, score DOUBLE NOT NULL, fetched_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_candidate_song ON metadata_candidate (song_id, fetched_at);
CREATE TABLE song_metadata (
  song_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
  track_json TEXT NOT NULL, fields_json TEXT NOT NULL, origins_json TEXT NOT NULL, applied_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_song_metadata_source ON song_metadata (source_id);
-- migrate:down
DROP TABLE song_metadata;
DROP TABLE metadata_candidate;
DROP TABLE metadata_item;
DROP TABLE metadata_batch;
DROP TABLE metadata_settings;
