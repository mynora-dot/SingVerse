-- migrate:up
CREATE TABLE metadata_cover_asset (
 id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 mime VARCHAR(32) NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
 created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_cover_asset_created ON metadata_cover_asset (created_at,id);
CREATE TABLE metadata_review_receipt (
 request_id VARCHAR(36) PRIMARY KEY, song_id VARCHAR(36) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 response_json TEXT NOT NULL, created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_review_receipt_created ON metadata_review_receipt (created_at);
-- migrate:down
DROP TABLE metadata_review_receipt;
DROP TABLE metadata_cover_asset;
