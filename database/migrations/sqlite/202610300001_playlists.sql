-- migrate:up
CREATE TABLE playlist (id VARCHAR(36) PRIMARY KEY,name VARCHAR(120) NOT NULL,description TEXT NOT NULL,song_ids_json TEXT NOT NULL,revision BIGINT NOT NULL,creation_hash VARCHAR(64) NOT NULL,created_at VARCHAR(40) NOT NULL,updated_at VARCHAR(40) NOT NULL);
CREATE TABLE playlist_lock (id INTEGER PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0);
INSERT INTO playlist_lock(id) VALUES(1);

-- migrate:down
DROP TABLE playlist_lock;
DROP TABLE playlist;
