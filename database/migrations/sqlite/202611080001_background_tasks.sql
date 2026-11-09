-- migrate:up
CREATE TABLE background_task (
  id VARCHAR(36) PRIMARY KEY,
  type VARCHAR(32) NOT NULL,
  business_id VARCHAR(100) NOT NULL,
  parent_id VARCHAR(36),
  name VARCHAR(512) NOT NULL,
  subject_name VARCHAR(512) NOT NULL,
  source_id VARCHAR(36),
  song_id VARCHAR(64),
  status VARCHAR(24) NOT NULL,
  business_status VARCHAR(30) NOT NULL,
  stage VARCHAR(80) NOT NULL,
  completed BIGINT,
  total BIGINT,
  progress_unit VARCHAR(16) NOT NULL,
  statistics_json TEXT NOT NULL,
  error VARCHAR(1000) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  started_at BIGINT,
  finished_at BIGINT,
  expires_at BIGINT,
  items_available INTEGER NOT NULL DEFAULT 0,
  time_incomplete INTEGER NOT NULL DEFAULT 0,
  fingerprint VARCHAR(64) NOT NULL,
  UNIQUE(type, business_id)
);
CREATE INDEX idx_background_task_status ON background_task(status, created_at, id);
CREATE INDEX idx_background_task_type ON background_task(type, created_at, id);
CREATE INDEX idx_background_task_expiry ON background_task(expires_at, id);

CREATE TABLE background_task_item (
  task_id VARCHAR(36) NOT NULL,
  id VARCHAR(100) NOT NULL,
  song_id VARCHAR(64),
  title VARCHAR(2048) NOT NULL,
  status VARCHAR(30) NOT NULL,
  message VARCHAR(1000) NOT NULL,
  execution_json TEXT NOT NULL,
  source_updated_at VARCHAR(50) NOT NULL,
  execution_updated_at BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(task_id, id)
);
CREATE INDEX idx_background_task_item_status ON background_task_item(task_id, status, id);

-- migrate:down
DROP TABLE background_task_item;
DROP TABLE background_task;
