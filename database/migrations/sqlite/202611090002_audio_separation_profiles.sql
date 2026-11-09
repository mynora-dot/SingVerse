-- migrate:up
ALTER TABLE media_audio_job ADD COLUMN recipe_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE audio_batch ADD COLUMN recipe_json TEXT NOT NULL DEFAULT '{}';
CREATE TABLE media_audio_selection (
  song_id VARCHAR(36) PRIMARY KEY,
  fingerprint VARCHAR(128) NOT NULL,
  job_id VARCHAR(36) NOT NULL,
  updated_at BIGINT NOT NULL
);

-- migrate:down
DROP TABLE media_audio_selection;
ALTER TABLE audio_batch DROP COLUMN recipe_json;
ALTER TABLE media_audio_job DROP COLUMN recipe_json;
