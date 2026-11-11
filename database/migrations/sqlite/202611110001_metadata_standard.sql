-- migrate:up
CREATE TABLE metadata_document (
 id VARCHAR(36) PRIMARY KEY,
 entity_type VARCHAR(16) NOT NULL,
 parent_id VARCHAR(36),
 revision VARCHAR(19) NOT NULL,
 document_json TEXT NOT NULL,
 identity_complete INTEGER NOT NULL DEFAULT 0,
 search_text TEXT NOT NULL DEFAULT '',
 display_title VARCHAR(500) NOT NULL DEFAULT '',
 suppressed INTEGER NOT NULL DEFAULT 0,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_metadata_kind_title ON metadata_document(entity_type,suppressed,display_title,id);
CREATE INDEX idx_metadata_parent ON metadata_document(parent_id,entity_type,id);
CREATE TABLE metadata_location (
 id VARCHAR(36) PRIMARY KEY,
 media_id VARCHAR(36) NOT NULL,
 source_id VARCHAR(36),
 relative_path VARCHAR(2048) NOT NULL,
 path_hash VARCHAR(64) NOT NULL,
 local_key VARCHAR(100),
 state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 observation_json TEXT NOT NULL,
 updated_at VARCHAR(40) NOT NULL,
 UNIQUE(source_id,path_hash)
);
CREATE INDEX idx_metadata_location_media ON metadata_location(media_id,state,id);
CREATE INDEX idx_metadata_location_state ON metadata_location(source_id,state,media_id);
CREATE INDEX idx_metadata_location_local ON metadata_location(source_id,local_key);
CREATE TABLE metadata_evidence (
 entity_id VARCHAR(36) NOT NULL,
 source_key VARCHAR(160) NOT NULL,
 revision VARCHAR(19) NOT NULL,
 digest VARCHAR(64) NOT NULL,
 evidence_json TEXT NOT NULL,
 PRIMARY KEY(entity_id,source_key)
);
CREATE TABLE metadata_difference (
 entity_id VARCHAR(36) NOT NULL,
 field_name VARCHAR(80) NOT NULL,
 source_key VARCHAR(160) NOT NULL,
 incoming_json TEXT NOT NULL,
 PRIMARY KEY(entity_id,field_name,source_key)
);
CREATE TABLE metadata_request (
 request_id VARCHAR(36) PRIMARY KEY,
 entity_id VARCHAR(36) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 response_json TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
CREATE TABLE metadata_asset (
 id VARCHAR(64) PRIMARY KEY,
 mime VARCHAR(100) NOT NULL,
 byte_size BIGINT NOT NULL,
 suffix VARCHAR(20) NOT NULL,
 width INTEGER,
 height INTEGER,
 created_at VARCHAR(40) NOT NULL,
 unreferenced_at VARCHAR(40)
);
CREATE TABLE metadata_asset_alias (
 legacy_id VARCHAR(100) PRIMARY KEY,
 asset_id VARCHAR(64) NOT NULL
);
CREATE TABLE metadata_asset_reference (
 owner_type VARCHAR(16) NOT NULL,
 owner_id VARCHAR(100) NOT NULL,
 asset_id VARCHAR(64) NOT NULL,
 expires_at VARCHAR(40),
 PRIMARY KEY(owner_type,owner_id,asset_id)
);
CREATE INDEX idx_metadata_asset_ref ON metadata_asset_reference(asset_id,expires_at);
CREATE TABLE metadata_package_revision (
 collection_id VARCHAR(36) NOT NULL,
 revision VARCHAR(19) NOT NULL,
 digest VARCHAR(64) NOT NULL,
 PRIMARY KEY(collection_id,revision)
);
ALTER TABLE media_song ADD COLUMN location_state VARCHAR(24) NOT NULL DEFAULT 'PRESENT';
ALTER TABLE media_import_job ADD COLUMN package_version INTEGER NOT NULL DEFAULT 3;
ALTER TABLE media_import_job ADD COLUMN content_digest VARCHAR(64) NOT NULL DEFAULT '';
CREATE TABLE metadata_attachment_task (
 media_id VARCHAR(36) PRIMARY KEY,
 cover_url VARCHAR(2048) NOT NULL,
 cover_revision VARCHAR(20) NOT NULL DEFAULT '',
 local_decision INTEGER NOT NULL DEFAULT 0,
 origin_json TEXT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 error VARCHAR(300),
 updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE metadata_identity_alias (
 source_id VARCHAR(36) NOT NULL,
 collection_id VARCHAR(36) NOT NULL,
 remote_id VARCHAR(36) NOT NULL,
 local_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(source_id,collection_id,remote_id)
);
ALTER TABLE webdav_share_job ADD COLUMN package_version INTEGER NOT NULL DEFAULT 3;
CREATE TABLE metadata_upgrade (name VARCHAR(100) PRIMARY KEY,value VARCHAR(2048) NOT NULL);
INSERT INTO metadata_upgrade(name,value) VALUES('shared-formal-1','');
CREATE INDEX idx_media_location_pending ON media_song(location_state,id);
-- migrate:down
DROP TABLE metadata_upgrade;
DROP INDEX idx_media_location_pending;
ALTER TABLE webdav_share_job DROP COLUMN package_version;
DROP TABLE metadata_identity_alias;
DROP TABLE metadata_attachment_task;
ALTER TABLE media_import_job DROP COLUMN content_digest;
ALTER TABLE media_import_job DROP COLUMN package_version;
ALTER TABLE media_song DROP COLUMN location_state;
DROP TABLE metadata_package_revision;
DROP TABLE metadata_asset_reference;
DROP TABLE metadata_asset_alias;
DROP TABLE metadata_asset;
DROP TABLE metadata_request;
DROP TABLE metadata_difference;
DROP TABLE metadata_evidence;
DROP TABLE metadata_location;
DROP TABLE metadata_document;
