-- migrate:up
CREATE TABLE media_audio_role (
 song_id VARCHAR(36) PRIMARY KEY, fingerprint VARCHAR(128) NOT NULL,
 original_id VARCHAR(16), backing_id VARCHAR(16), origin VARCHAR(16) NOT NULL,
 revision BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE media_audio_job (
 id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL,
 state VARCHAR(16) NOT NULL, fingerprint VARCHAR(128) NOT NULL, node_origin VARCHAR(2048) NOT NULL,
 result_json TEXT NOT NULL, error VARCHAR(200) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX media_audio_job_pending ON media_audio_job(state,created_at);
CREATE INDEX media_audio_job_song ON media_audio_job(song_id,kind,fingerprint);
CREATE TABLE media_audio_import (song_id VARCHAR(36) PRIMARY KEY, claim_json TEXT NOT NULL);
-- migrate:down
DROP TABLE media_audio_import;
DROP TABLE media_audio_job;
DROP TABLE media_audio_role;
