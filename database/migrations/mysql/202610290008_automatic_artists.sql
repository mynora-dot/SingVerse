-- migrate:up
ALTER TABLE artist MODIFY name VARCHAR(500) NOT NULL;
ALTER TABLE artist ADD COLUMN name_key VARCHAR(64);
CREATE UNIQUE INDEX idx_artist_name_key ON artist(name_key);
ALTER TABLE artist ADD COLUMN fetch_state VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE artist ADD COLUMN fetch_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE artist ADD COLUMN fetch_after VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE artist ADD COLUMN fetch_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE artist_image ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'UPLOAD';
ALTER TABLE artist_image ADD COLUMN provider VARCHAR(20);
ALTER TABLE artist_image ADD COLUMN external_id VARCHAR(120);
ALTER TABLE artist_image ADD COLUMN source_url TEXT;
CREATE TABLE artist_image_policy (artist_id VARCHAR(36) NOT NULL, image_kind VARCHAR(16) NOT NULL, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(artist_id,image_kind));
CREATE TABLE song_artist_override (source_id VARCHAR(36) NOT NULL, path_key VARCHAR(64) NOT NULL, artists_json TEXT, revision BIGINT NOT NULL, PRIMARY KEY(source_id,path_key));

-- migrate:down
DROP TABLE song_artist_override;
DROP TABLE artist_image_policy;
ALTER TABLE artist_image DROP COLUMN source_url;
ALTER TABLE artist_image DROP COLUMN external_id;
ALTER TABLE artist_image DROP COLUMN provider;
ALTER TABLE artist_image DROP COLUMN origin;
ALTER TABLE artist DROP COLUMN fetch_generation;
ALTER TABLE artist DROP COLUMN fetch_after;
ALTER TABLE artist DROP COLUMN fetch_attempts;
ALTER TABLE artist DROP COLUMN fetch_state;
DROP INDEX idx_artist_name_key ON artist;
ALTER TABLE artist DROP COLUMN name_key;
