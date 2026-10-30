-- migrate:up
CREATE TABLE media_transcode (
 id VARCHAR(36) PRIMARY KEY, execution_id VARCHAR(36) NOT NULL, pipeline_version VARCHAR(16) NOT NULL DEFAULT '1', song_id VARCHAR(64) NOT NULL, owner_id VARCHAR(100) NOT NULL,
 purpose VARCHAR(16) NOT NULL, state VARCHAR(24) NOT NULL, fingerprint VARCHAR(128) NOT NULL,
 node_origin VARCHAR(512) NOT NULL, input_json TEXT NOT NULL, target_json TEXT NOT NULL,
 result_json TEXT NOT NULL, error TEXT NOT NULL, renewed_at BIGINT NOT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX media_transcode_pending ON media_transcode(purpose,state,created_at);
CREATE INDEX media_transcode_song ON media_transcode(song_id,fingerprint,state);
-- migrate:down
DROP TABLE media_transcode;
