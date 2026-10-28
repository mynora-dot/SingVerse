-- migrate:up
CREATE TABLE classification_settings (
 id INTEGER PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0, endpoint VARCHAR(2048) NOT NULL DEFAULT '',
 model VARCHAR(200) NOT NULL DEFAULT '', secret_encrypted TEXT, timeout_seconds INTEGER NOT NULL DEFAULT 60,
 interval_seconds INTEGER NOT NULL DEFAULT 3, revision BIGINT NOT NULL DEFAULT 0, next_request_at VARCHAR(40) NOT NULL DEFAULT ''
);
INSERT INTO classification_settings (id) VALUES (1);
CREATE TABLE classification_tag (
 id VARCHAR(36) PRIMARY KEY, dimension VARCHAR(20) NOT NULL, name VARCHAR(80) NOT NULL,
 aliases_json TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1, revision BIGINT NOT NULL DEFAULT 1,
 UNIQUE (dimension, name)
);
CREATE TABLE classification_song (
 song_id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL, state_json TEXT NOT NULL
);
CREATE TABLE classification_assignment (
 song_id VARCHAR(36) NOT NULL, dimension VARCHAR(20) NOT NULL, tag_id VARCHAR(36) NOT NULL,
 PRIMARY KEY (song_id, dimension, tag_id)
);
CREATE INDEX idx_classification_tag_song ON classification_assignment (tag_id, song_id);
CREATE INDEX idx_classification_dimension_song ON classification_assignment (dimension, song_id);
CREATE TABLE classification_task (
 id VARCHAR(36) PRIMARY KEY, status VARCHAR(20) NOT NULL, config_json TEXT NOT NULL,
 vocabulary_json TEXT NOT NULL, dimensions_json TEXT NOT NULL, automatic_json TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, next_at VARCHAR(40) NOT NULL
);
CREATE TABLE classification_item (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, song_id VARCHAR(36) NOT NULL,
 status VARCHAR(20) NOT NULL, input_json TEXT NOT NULL, input_version VARCHAR(64) NOT NULL,
 song_revision BIGINT NOT NULL, suggestion_json TEXT, error VARCHAR(1000), updated_at VARCHAR(40) NOT NULL,
 UNIQUE (task_id, song_id)
);
CREATE INDEX idx_classification_task_items ON classification_item (task_id, status, id);
CREATE TABLE classification_history (
 id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, task_id VARCHAR(36),
 origin VARCHAR(20) NOT NULL, before_json TEXT NOT NULL, after_json TEXT NOT NULL,
 after_revision BIGINT NOT NULL, reverted INTEGER NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_classification_history_song ON classification_history (song_id, created_at, id);
CREATE TABLE classification_receipt (
 id VARCHAR(36) PRIMARY KEY, request_hash VARCHAR(64) NOT NULL, response_json TEXT NOT NULL
);
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-0', 'LANGUAGE', '国语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-1', 'LANGUAGE', '粤语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-2', 'LANGUAGE', '英语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-3', 'LANGUAGE', '日语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-4', 'LANGUAGE', '韩语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-5', 'LANGUAGE', '多语种', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-6', 'LANGUAGE', '其他', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-0', 'DECADE', '1950 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-1', 'DECADE', '1960 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-2', 'DECADE', '1970 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-3', 'DECADE', '1980 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-4', 'DECADE', '1990 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-5', 'DECADE', '2000 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-6', 'DECADE', '2010 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-7', 'DECADE', '2020 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-0', 'GENRE', '流行', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-1', 'GENRE', '摇滚', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-2', 'GENRE', '民谣', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-3', 'GENRE', '电子', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-4', 'GENRE', '说唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-5', 'GENRE', '爵士', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-6', 'GENRE', '古典', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-7', 'GENRE', 'R&B', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-0', 'MOOD', '欢快', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-1', 'MOOD', '伤感', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-2', 'MOOD', '温柔', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-3', 'MOOD', '激昂', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-4', 'MOOD', '平静', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-0', 'SCENE', '聚会', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-1', 'SCENE', '怀旧', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-2', 'SCENE', '独唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-3', 'SCENE', '合唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('custom-0', 'CUSTOM', '家庭常唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('custom-1', 'CUSTOM', '长辈喜爱', '[]');
-- migrate:down
DROP TABLE classification_receipt;
DROP TABLE classification_history;
DROP TABLE classification_item;
DROP TABLE classification_task;
DROP TABLE classification_assignment;
DROP TABLE classification_song;
DROP TABLE classification_tag;
DROP TABLE classification_settings;
