-- migrate:up
CREATE TABLE artist_image_fetch (artist_id VARCHAR(36) NOT NULL, image_kind VARCHAR(16) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INTEGER NOT NULL DEFAULT 0, fetch_after VARCHAR(40) NOT NULL DEFAULT '', generation BIGINT NOT NULL DEFAULT 0, candidates_json TEXT, PRIMARY KEY(artist_id,image_kind));
INSERT INTO artist_image_fetch(artist_id,image_kind,state) SELECT id,'PORTRAIT','PENDING' FROM artist WHERE name_key IS NOT NULL;
INSERT INTO artist_image_fetch(artist_id,image_kind,state) SELECT id,'BACKGROUND','PENDING' FROM artist WHERE name_key IS NOT NULL;
ALTER TABLE artist_image ADD COLUMN attribution TEXT;
CREATE TABLE artist_artwork_settings (id INTEGER PRIMARY KEY, fanart_key_encrypted TEXT);
INSERT INTO artist_artwork_settings(id) VALUES(1);
-- migrate:down
DROP TABLE artist_artwork_settings;
ALTER TABLE artist_image DROP COLUMN attribution;
DROP TABLE artist_image_fetch;
