-- migrate:up
-- Keep the original operation state; batch state has its own lifetime and retention clock.
ALTER TABLE background_task ADD COLUMN is_batch INTEGER NOT NULL DEFAULT 1;
ALTER TABLE background_task ADD COLUMN batch_kind VARCHAR(24) NOT NULL DEFAULT 'UNCLASSIFIED';
ALTER TABLE background_task ADD COLUMN scope_sealed INTEGER NOT NULL DEFAULT 1;
ALTER TABLE background_task ADD COLUMN batch_status VARCHAR(24);
ALTER TABLE background_task ADD COLUMN batch_stage VARCHAR(80);
ALTER TABLE background_task ADD COLUMN batch_counts_json TEXT;
ALTER TABLE background_task ADD COLUMN batch_finished_at BIGINT;
ALTER TABLE background_task ADD COLUMN batch_expires_at BIGINT;
ALTER TABLE background_task ADD COLUMN batch_updated_at BIGINT;
UPDATE background_task SET is_batch=CASE WHEN parent_id IS NULL THEN 1 ELSE 0 END,
  batch_status=status,batch_stage=stage,batch_finished_at=finished_at,batch_expires_at=expires_at;
CREATE INDEX idx_background_batch_order ON background_task(is_batch,batch_status,created_at,id);
CREATE INDEX idx_background_batch_expiry ON background_task(batch_expires_at,id);

-- A reused job can participate in several operations without losing its original owner.
CREATE TABLE background_task_relation (
  batch_id VARCHAR(36) NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY(batch_id,task_id)
);
CREATE INDEX idx_background_relation_task ON background_task_relation(task_id,batch_id);
INSERT INTO background_task_relation(batch_id,task_id,created_at)
  SELECT parent_id,id,created_at FROM background_task WHERE parent_id IS NOT NULL;

-- Processing admission, not a scan file log. A successful probe still waits for the FAST decision.
CREATE TABLE background_scan_scope (
  batch_id VARCHAR(36) NOT NULL,
  song_id VARCHAR(64) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  current_job_id VARCHAR(36),
  reason VARCHAR(1000) NOT NULL,
  uncertain INTEGER NOT NULL DEFAULT 0,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY(batch_id,song_id,fingerprint)
);
CREATE INDEX idx_background_scope_song ON background_scan_scope(song_id,fingerprint,state);
CREATE INDEX idx_background_scope_job ON background_scan_scope(current_job_id,state);
CREATE INDEX idx_background_scope_state ON background_scan_scope(state,batch_id,song_id);

-- migrate:down
DROP TABLE background_scan_scope;
DROP TABLE background_task_relation;
DROP INDEX idx_background_batch_expiry ON background_task;
DROP INDEX idx_background_batch_order ON background_task;
ALTER TABLE background_task DROP COLUMN batch_updated_at;
ALTER TABLE background_task DROP COLUMN batch_expires_at;
ALTER TABLE background_task DROP COLUMN batch_finished_at;
ALTER TABLE background_task DROP COLUMN batch_counts_json;
ALTER TABLE background_task DROP COLUMN batch_stage;
ALTER TABLE background_task DROP COLUMN batch_status;
ALTER TABLE background_task DROP COLUMN scope_sealed;
ALTER TABLE background_task DROP COLUMN batch_kind;
ALTER TABLE background_task DROP COLUMN is_batch;
