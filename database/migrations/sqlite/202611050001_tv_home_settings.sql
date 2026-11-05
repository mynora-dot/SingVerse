-- migrate:up
CREATE TABLE tv_home_settings (
 id INTEGER PRIMARY KEY,
 headline VARCHAR(80) NOT NULL,
 subtitle VARCHAR(160) NOT NULL,
 auto_rotate INTEGER NOT NULL DEFAULT 1,
 rotation_seconds INTEGER NOT NULL DEFAULT 8,
 revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO tv_home_settings(id,headline,subtitle) VALUES(1,'把生活，唱成热爱。','你的私人音乐现场');
CREATE TABLE tv_home_banner (
 id VARCHAR(64) PRIMARY KEY,
 label VARCHAR(80) NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1,
 sort_order INTEGER NOT NULL,
 version VARCHAR(64) NOT NULL,
 image_bytes BLOB NOT NULL
);
-- migrate:down
DROP TABLE tv_home_banner;
DROP TABLE tv_home_settings;
