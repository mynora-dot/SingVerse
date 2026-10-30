-- migrate:up
CREATE TABLE tv_record (
 id VARCHAR(64) PRIMARY KEY,
 kind VARCHAR(24) NOT NULL,
 secret_hash VARCHAR(64),
 document TEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX tv_record_secret ON tv_record(secret_hash);
CREATE INDEX tv_record_kind ON tv_record(kind,id);
-- migrate:down
DROP TABLE tv_record;
