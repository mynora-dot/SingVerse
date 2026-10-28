-- migrate:up
ALTER TABLE media_source ADD COLUMN parser_profile_id VARCHAR(36) NOT NULL DEFAULT 'default';
CREATE INDEX idx_media_source_parser_profile ON media_source (parser_profile_id);

-- migrate:down
DROP INDEX idx_media_source_parser_profile;
ALTER TABLE media_source DROP COLUMN parser_profile_id;
