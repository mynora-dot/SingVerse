-- migrate:up
ALTER TABLE media_song ADD COLUMN embedded_tags_json TEXT;
-- migrate:down
ALTER TABLE media_song DROP COLUMN embedded_tags_json;
