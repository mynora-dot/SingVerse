-- migrate:up
-- Snapshots deliberately have no foreign key to songs, sources or devices: history survives removal.
CREATE TABLE playback_history (
 id VARCHAR(36) PRIMARY KEY,
 song_id VARCHAR(64) NOT NULL,
 title VARCHAR(500) NOT NULL,
 artists_json TEXT NOT NULL DEFAULT '[]',
 version_label VARCHAR(200) NOT NULL DEFAULT '',
 source_name VARCHAR(200) NOT NULL DEFAULT '',
 client_type VARCHAR(16) NOT NULL CHECK(client_type IN ('TV','MOBILE','ADMIN')),
 device_name VARCHAR(200) NOT NULL DEFAULT '',
 requested_by VARCHAR(200) NOT NULL DEFAULT '',
 session_id VARCHAR(64),
 attempt_id VARCHAR(64),
 started_at BIGINT NOT NULL,
 ended_at BIGINT,
 played_ms BIGINT NOT NULL DEFAULT 0 CHECK(played_ms >= 0),
 duration_ms BIGINT CHECK(duration_ms IS NULL OR duration_ms > 0),
 status VARCHAR(16) NOT NULL CHECK(status IN ('PLAYING','COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')),
 CHECK(ended_at IS NULL OR ended_at >= started_at),
 UNIQUE(client_type,attempt_id)
);
CREATE INDEX playback_history_recent ON playback_history(started_at,id);
CREATE INDEX playback_history_song_recent ON playback_history(song_id,started_at,id);
-- migrate:down
DROP TABLE playback_history;
