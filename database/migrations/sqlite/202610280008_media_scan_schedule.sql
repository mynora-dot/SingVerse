-- migrate:up
ALTER TABLE media_source ADD COLUMN scan_enabled INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_source ADD COLUMN scan_cron VARCHAR(120);
ALTER TABLE media_source ADD COLUMN next_scan_at VARCHAR(30);
ALTER TABLE media_source ADD COLUMN last_scan_at VARCHAR(30);
ALTER TABLE media_source ADD COLUMN last_scan_status VARCHAR(24);
ALTER TABLE media_source ADD COLUMN last_scan_error VARCHAR(500);
CREATE INDEX idx_source_scan_due ON media_source (scan_enabled,next_scan_at,id);
-- migrate:down
DROP INDEX idx_source_scan_due;
ALTER TABLE media_source DROP COLUMN last_scan_error;
ALTER TABLE media_source DROP COLUMN last_scan_status;
ALTER TABLE media_source DROP COLUMN last_scan_at;
ALTER TABLE media_source DROP COLUMN next_scan_at;
ALTER TABLE media_source DROP COLUMN scan_cron;
ALTER TABLE media_source DROP COLUMN scan_enabled;
