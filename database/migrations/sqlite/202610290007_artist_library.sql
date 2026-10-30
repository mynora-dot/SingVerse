-- migrate:up
CREATE TABLE artist_registry_lock (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0);
INSERT INTO artist_registry_lock(id) VALUES (1);
CREATE TABLE artist (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(120) NOT NULL, aliases_json TEXT NOT NULL,
 artist_type VARCHAR(20) NOT NULL, biography TEXT NOT NULL, active INTEGER NOT NULL,
 revision BIGINT NOT NULL, creation_hash VARCHAR(64) NOT NULL,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE artist_image (
 artist_id VARCHAR(36) NOT NULL, image_kind VARCHAR(16) NOT NULL,
 version VARCHAR(64) NOT NULL, image_bytes BLOB NOT NULL,
 PRIMARY KEY (artist_id,image_kind), FOREIGN KEY (artist_id) REFERENCES artist(id)
);
CREATE TABLE artist_credit_binding (
 song_id VARCHAR(36) NOT NULL, credit_name VARCHAR(500) NOT NULL, artist_id VARCHAR(36),
 revision BIGINT NOT NULL,
 PRIMARY KEY (song_id,credit_name), FOREIGN KEY (artist_id) REFERENCES artist(id)
);
CREATE INDEX idx_artist_credit_artist ON artist_credit_binding(artist_id,song_id);

-- migrate:down
DROP TABLE artist_credit_binding;
DROP TABLE artist_image;
DROP TABLE artist;
DROP TABLE artist_registry_lock;
