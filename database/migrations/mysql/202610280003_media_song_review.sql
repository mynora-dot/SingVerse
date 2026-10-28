-- migrate:up
ALTER TABLE media_song ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 1;
UPDATE media_song SET needs_review = CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.needsReview')) = 'false' THEN 0 ELSE 1 END;
CREATE INDEX idx_media_song_review ON media_song (needs_review, id);

-- migrate:down
DROP INDEX idx_media_song_review ON media_song;
ALTER TABLE media_song DROP COLUMN needs_review;
