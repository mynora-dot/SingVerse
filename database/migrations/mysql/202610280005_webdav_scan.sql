-- migrate:up
ALTER TABLE media_song MODIFY file_size BIGINT NULL, MODIFY modified_at VARCHAR(30) NULL;
CREATE TABLE media_scan_task (
  id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL,
  active_source VARCHAR(36) UNIQUE, status VARCHAR(20) NOT NULL, phase VARCHAR(20) NOT NULL,
  directories BIGINT NOT NULL DEFAULT 0, entries BIGINT NOT NULL DEFAULT 0,
  total_songs INTEGER NOT NULL DEFAULT 0, matched_songs INTEGER NOT NULL DEFAULT 0,
  review_songs INTEGER NOT NULL DEFAULT 0, skipped_files BIGINT NOT NULL DEFAULT 0,
  cancel_requested INTEGER NOT NULL DEFAULT 0, error VARCHAR(500),
  created_at VARCHAR(30) NOT NULL, started_at VARCHAR(30), finished_at VARCHAR(30)
);
CREATE INDEX idx_scan_task_source ON media_scan_task (source_id, created_at, id);
CREATE TABLE media_scan_directory (
  task_id VARCHAR(36) NOT NULL, path_hash VARCHAR(64) NOT NULL,
  path VARCHAR(4096) NOT NULL, depth INTEGER NOT NULL, processed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (task_id, path_hash)
);
CREATE INDEX idx_scan_directory_pending ON media_scan_directory (task_id, processed);
CREATE TABLE media_scan_song (
  task_id VARCHAR(36) NOT NULL, id VARCHAR(36) NOT NULL, source_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL, path_hash VARCHAR(64) NOT NULL,
  file_size BIGINT, modified_at VARCHAR(30), scanned_at VARCHAR(30) NOT NULL,
  parser_profile_id VARCHAR(36) NOT NULL, parser_revision BIGINT NOT NULL,
  selected_rule_id VARCHAR(100), result_json TEXT NOT NULL, needs_review INTEGER NOT NULL,
  PRIMARY KEY (task_id, id)
);

-- migrate:down
DROP TABLE media_scan_song;
DROP TABLE media_scan_directory;
DROP TABLE media_scan_task;
