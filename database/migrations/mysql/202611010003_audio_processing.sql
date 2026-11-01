-- migrate:up
ALTER TABLE media_audio_job ADD COLUMN backend VARCHAR(16) NOT NULL DEFAULT '';
CREATE TABLE audio_batch (
 id VARCHAR(36) PRIMARY KEY, state VARCHAR(16) NOT NULL, backend VARCHAR(16) NOT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE audio_batch_item (
 batch_id VARCHAR(36) NOT NULL, song_id VARCHAR(36) NOT NULL, fingerprint VARCHAR(128) NOT NULL,
 title VARCHAR(2048) NOT NULL, category VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL,
 job_id VARCHAR(36), error VARCHAR(200) NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(batch_id,song_id)
);
CREATE INDEX audio_batch_item_pending ON audio_batch_item(state,updated_at);
-- migrate:down
DROP TABLE audio_batch_item;
DROP TABLE audio_batch;
ALTER TABLE media_audio_job DROP COLUMN backend;
