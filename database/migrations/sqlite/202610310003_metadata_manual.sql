-- migrate:up
ALTER TABLE webdav_metadata_task ADD COLUMN manual INTEGER NOT NULL DEFAULT 0;
-- migrate:down
ALTER TABLE webdav_metadata_task DROP COLUMN manual;
