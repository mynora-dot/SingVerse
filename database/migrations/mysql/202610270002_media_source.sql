-- migrate:up
CREATE TABLE media_source (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  location VARCHAR(2048) NOT NULL,
  location_hash VARCHAR(64) NOT NULL UNIQUE,
  username VARCHAR(256) NOT NULL,
  password_encrypted TEXT,
  created_at VARCHAR(30) NOT NULL,
  updated_at VARCHAR(30) NOT NULL
);
-- migrate:down
DROP TABLE media_source;
