-- migrate:up
CREATE INDEX idx_song_scanned_page ON media_song (scanned_at DESC, id ASC);
CREATE INDEX idx_song_source_page ON media_song (source_id, scanned_at DESC, id ASC);
CREATE INDEX idx_song_review_page ON media_song (needs_review, scanned_at DESC, id ASC);
CREATE INDEX idx_song_source_review_page ON media_song (source_id, needs_review, scanned_at DESC, id ASC);
CREATE INDEX idx_source_created_page ON media_source (created_at DESC, id ASC);
CREATE INDEX idx_source_type_page ON media_source (source_type, created_at DESC, id ASC);
CREATE INDEX idx_source_profile_page ON media_source (parser_profile_id, created_at DESC, id ASC);

-- migrate:down
DROP INDEX idx_source_profile_page;
DROP INDEX idx_source_type_page;
DROP INDEX idx_source_created_page;
DROP INDEX idx_song_source_review_page;
DROP INDEX idx_song_review_page;
DROP INDEX idx_song_source_page;
DROP INDEX idx_song_scanned_page;
