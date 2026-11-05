-- migrate:up
CREATE TABLE song_cache_settings (
 id INTEGER PRIMARY KEY,
 max_bytes BIGINT NOT NULL DEFAULT 0,
 policy VARCHAR(16) NOT NULL DEFAULT 'LRU',
 ttl_hours INTEGER NOT NULL DEFAULT 168,
 revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO song_cache_settings(id) VALUES(1);
CREATE TABLE song_cache_entry (
 id VARCHAR(36) PRIMARY KEY,
 cache_key VARCHAR(64) NOT NULL UNIQUE,
 song_id VARCHAR(64) NOT NULL,
 execution_json TEXT NOT NULL,
 target_json TEXT NOT NULL,
 artifacts_json TEXT NOT NULL,
 media_type VARCHAR(16) NOT NULL,
 bytes BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 last_access_at BIGINT NOT NULL,
 hits BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE song_cache_lease (
 session_id VARCHAR(36) PRIMARY KEY,
 cache_id VARCHAR(36) NOT NULL REFERENCES song_cache_entry(id) ON DELETE CASCADE,
 renewed_at BIGINT NOT NULL
);
CREATE INDEX song_cache_lease_active ON song_cache_lease(cache_id,renewed_at);
-- migrate:down
DROP TABLE song_cache_lease;
DROP TABLE song_cache_entry;
DROP TABLE song_cache_settings;
