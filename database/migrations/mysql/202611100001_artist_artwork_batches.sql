-- migrate:up
CREATE TABLE artist_artwork_batch (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  request_id VARCHAR(36) NOT NULL UNIQUE,
  request_hash VARCHAR(64) NOT NULL,
  provider VARCHAR(16) NOT NULL,
  status VARCHAR(20) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  finished_at VARCHAR(40)
);
CREATE TABLE artist_artwork_item (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  artist_id VARCHAR(36) NOT NULL,
  artist_name VARCHAR(500) NOT NULL,
  ordinal INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  message VARCHAR(500) NOT NULL DEFAULT '',
  generation BIGINT NOT NULL DEFAULT 0,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE(batch_id,artist_id),
  FOREIGN KEY(batch_id) REFERENCES artist_artwork_batch(id),
  INDEX idx_artist_artwork_pending(status,batch_id,ordinal)
);
CREATE INDEX idx_artist_artwork_batch_order ON artist_artwork_batch(created_at,id);
-- migrate:down
DROP TABLE artist_artwork_item;
DROP TABLE artist_artwork_batch;
