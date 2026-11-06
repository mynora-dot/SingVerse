-- migrate:up
CREATE TABLE missing_song_feedback (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    artist VARCHAR(200) NOT NULL,
    version_label VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    submitted_by VARCHAR(80) NOT NULL,
    source VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    handling_note TEXT NOT NULL,
    resolved_song_id VARCHAR(64),
    resolved_song_title TEXT,
    resolved_song_artists_json TEXT NOT NULL,
    resolved_at VARCHAR(40),
    search_text TEXT NOT NULL,
    creation_hash VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX missing_song_feedback_created ON missing_song_feedback(created_at DESC, id);
CREATE INDEX missing_song_feedback_status_created ON missing_song_feedback(status, created_at DESC, id);
CREATE TABLE missing_song_feedback_lock (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0);
INSERT INTO missing_song_feedback_lock(id) VALUES(1);

-- migrate:down
DROP TABLE missing_song_feedback_lock;
DROP TABLE missing_song_feedback;
