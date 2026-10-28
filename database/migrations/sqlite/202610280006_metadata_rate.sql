-- migrate:up
ALTER TABLE metadata_settings ADD COLUMN min_interval_seconds INTEGER NOT NULL DEFAULT 3;
ALTER TABLE metadata_settings ADD COLUMN max_interval_seconds INTEGER NOT NULL DEFAULT 8;
-- migrate:down
ALTER TABLE metadata_settings DROP COLUMN max_interval_seconds;
ALTER TABLE metadata_settings DROP COLUMN min_interval_seconds;
