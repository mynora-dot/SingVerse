-- migrate:up
CREATE TABLE media_metadata_file (
 song_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 1,
 synced_revision BIGINT NOT NULL DEFAULT 0, source_location VARCHAR(2048), result_json LONGTEXT,
 error VARCHAR(200), updated_at VARCHAR(40)
);
CREATE INDEX idx_metadata_file_pending ON media_metadata_file(synced_revision,revision);
INSERT INTO media_metadata_file(song_id) SELECT id FROM media_song;
-- migrate:down
DROP TABLE media_metadata_file;
