-- migrate:up
ALTER TABLE media_song ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 1;
UPDATE media_song SET needs_review = CASE WHEN json_extract(result_json, '$.needsReview') = 0 THEN 0 ELSE 1 END;
CREATE INDEX idx_media_song_review ON media_song (needs_review, id);

-- migrate:down
DROP INDEX idx_media_song_review;
ALTER TABLE media_song DROP COLUMN needs_review;
