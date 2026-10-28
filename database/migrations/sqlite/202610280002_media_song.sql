-- migrate:up
CREATE TABLE media_song (
  id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  file_size BIGINT NOT NULL,
  modified_at VARCHAR(30) NOT NULL,
  scanned_at VARCHAR(30) NOT NULL,
  parser_profile_id VARCHAR(36) NOT NULL,
  parser_revision BIGINT NOT NULL,
  selected_rule_id VARCHAR(100),
  result_json TEXT NOT NULL,
  UNIQUE (source_id, path_hash)
);
CREATE INDEX idx_media_song_source ON media_song (source_id, id);

-- migrate:down
DROP TABLE media_song;
