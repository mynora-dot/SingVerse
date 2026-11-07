-- migrate:up
-- Fresh deployment schema. All columns, constraints and indexes are defined directly.

-- Administrator authentication
CREATE TABLE auth_account (
  id INTEGER PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(256) NOT NULL,
  default_password INTEGER NOT NULL,
  mfa_secret VARCHAR(512),
  pending_secret VARCHAR(512),
  pending_attempts INTEGER NOT NULL DEFAULT 0,
  pending_expires BIGINT NOT NULL DEFAULT 0,
  last_totp_step BIGINT NOT NULL DEFAULT -1,
  revision BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE auth_token (
  token_hash VARCHAR(64) PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  csrf VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  last_seen BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL
);

CREATE TABLE auth_recovery (
  code_hash VARCHAR(64) PRIMARY KEY
);

CREATE TABLE auth_throttle (
  bucket VARCHAR(128) PRIMARY KEY,
  failures INTEGER NOT NULL,
  blocked_until BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE auth_event (
  id VARCHAR(36) PRIMARY KEY,
  event_type VARCHAR(48) NOT NULL,
  occurred_at BIGINT NOT NULL
);


-- Media sources, filename rules and song library
CREATE TABLE media_source (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  location VARCHAR(2048) NOT NULL,
  location_hash VARCHAR(64) NOT NULL UNIQUE,
  username VARCHAR(256) NOT NULL,
  password_encrypted TEXT,
  created_at VARCHAR(30) NOT NULL,
  updated_at VARCHAR(30) NOT NULL,
  parser_profile_id VARCHAR(36) NOT NULL DEFAULT 'default',
  scan_enabled INTEGER NOT NULL DEFAULT 0,
  scan_cron VARCHAR(120),
  next_scan_at VARCHAR(30),
  last_scan_at VARCHAR(30),
  last_scan_status VARCHAR(24),
  last_scan_error VARCHAR(500)
);
CREATE INDEX idx_media_source_parser_profile ON media_source (parser_profile_id);
CREATE INDEX idx_source_created_page ON media_source (created_at DESC, id ASC);
CREATE INDEX idx_source_type_page ON media_source (source_type, created_at DESC, id ASC);
CREATE INDEX idx_source_profile_page ON media_source (parser_profile_id, created_at DESC, id ASC);
CREATE INDEX idx_source_scan_due ON media_source (scan_enabled,next_scan_at,id);

CREATE TABLE filename_parser_config (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  revision BIGINT NOT NULL,
  rules_json TEXT NOT NULL,
  updated_at VARCHAR(30) NOT NULL
);

CREATE TABLE media_song (
  id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  file_size BIGINT,
  modified_at VARCHAR(30),
  scanned_at VARCHAR(30) NOT NULL,
  parser_profile_id VARCHAR(36) NOT NULL,
  parser_revision BIGINT NOT NULL,
  selected_rule_id VARCHAR(100),
  result_json TEXT NOT NULL,
  needs_review INTEGER NOT NULL DEFAULT 1,
  embedded_tags_json TEXT,
  UNIQUE (source_id, path_hash)
);
CREATE INDEX idx_media_song_source ON media_song (source_id, id);
CREATE INDEX idx_media_song_review ON media_song (needs_review, id);
CREATE INDEX idx_song_scanned_page ON media_song (scanned_at DESC, id ASC);
CREATE INDEX idx_song_source_page ON media_song (source_id, scanned_at DESC, id ASC);
CREATE INDEX idx_song_review_page ON media_song (needs_review, scanned_at DESC, id ASC);
CREATE INDEX idx_song_source_review_page ON media_song (source_id, needs_review, scanned_at DESC, id ASC);


-- Local and WebDAV scans
CREATE TABLE media_scan_task (
  id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  active_source VARCHAR(36) UNIQUE,
  status VARCHAR(20) NOT NULL,
  phase VARCHAR(20) NOT NULL,
  directories BIGINT NOT NULL DEFAULT 0,
  entries BIGINT NOT NULL DEFAULT 0,
  total_songs INTEGER NOT NULL DEFAULT 0,
  matched_songs INTEGER NOT NULL DEFAULT 0,
  review_songs INTEGER NOT NULL DEFAULT 0,
  skipped_files BIGINT NOT NULL DEFAULT 0,
  cancel_requested INTEGER NOT NULL DEFAULT 0,
  error VARCHAR(500),
  created_at VARCHAR(30) NOT NULL,
  started_at VARCHAR(30),
  finished_at VARCHAR(30),
  added_songs INTEGER NOT NULL DEFAULT 0,
  reused_songs INTEGER NOT NULL DEFAULT 0,
  missing_songs INTEGER NOT NULL DEFAULT 0,
  removed_songs INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_scan_task_source ON media_scan_task (source_id, created_at, id);

CREATE TABLE media_scan_directory (
  task_id VARCHAR(36) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  path VARCHAR(4096) NOT NULL,
  depth INTEGER NOT NULL,
  processed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (task_id, path_hash)
);
CREATE INDEX idx_scan_directory_pending ON media_scan_directory (task_id, processed);

CREATE TABLE media_scan_song (
  task_id VARCHAR(36) NOT NULL,
  id VARCHAR(36) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  file_size BIGINT,
  modified_at VARCHAR(30),
  scanned_at VARCHAR(30) NOT NULL,
  parser_profile_id VARCHAR(36) NOT NULL,
  parser_revision BIGINT NOT NULL,
  selected_rule_id VARCHAR(100),
  result_json TEXT NOT NULL,
  needs_review INTEGER NOT NULL,
  etag VARCHAR(512),
  PRIMARY KEY (task_id, id)
);


-- Metadata settings, scraping and review
CREATE TABLE metadata_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  enabled INTEGER NOT NULL DEFAULT 0,
  providers_json TEXT NOT NULL,
  auto_apply INTEGER NOT NULL DEFAULT 0,
  threshold DOUBLE NOT NULL DEFAULT 0.95,
  updated_at VARCHAR(30) NOT NULL,
  min_interval_seconds INTEGER NOT NULL DEFAULT 3,
  max_interval_seconds INTEGER NOT NULL DEFAULT 8
);
INSERT INTO metadata_settings (id, enabled, providers_json, auto_apply, threshold, updated_at, min_interval_seconds, max_interval_seconds) VALUES (1, 0, '["NETEASE"]', 0, 0.95, '2026-10-28T00:00:00Z', 3, 8);

CREATE TABLE metadata_batch (
  id VARCHAR(36) PRIMARY KEY,
  mode VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  providers_json TEXT NOT NULL,
  auto_apply INTEGER NOT NULL,
  threshold DOUBLE NOT NULL,
  skipped_existing INTEGER NOT NULL DEFAULT 0,
  created_at VARCHAR(30) NOT NULL,
  updated_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_batch_created ON metadata_batch (created_at, id);

CREATE TABLE metadata_item (
  id VARCHAR(36) PRIMARY KEY,
  batch_id VARCHAR(36) NOT NULL,
  song_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  title VARCHAR(1024),
  artists_json TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  candidates_json TEXT,
  error_message VARCHAR(500),
  updated_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_item_batch ON metadata_item (batch_id, status, id);

CREATE TABLE metadata_candidate (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  track_json TEXT NOT NULL,
  score DOUBLE NOT NULL,
  fetched_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_metadata_candidate_song ON metadata_candidate (song_id, fetched_at);

CREATE TABLE song_metadata (
  song_id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  track_json TEXT NOT NULL,
  fields_json TEXT NOT NULL,
  origins_json TEXT NOT NULL,
  applied_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_song_metadata_source ON song_metadata (source_id);

CREATE TABLE metadata_cover_asset (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  mime VARCHAR(32) NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_cover_asset_created ON metadata_cover_asset (created_at,id);

CREATE TABLE metadata_review_receipt (
  request_id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  response_json TEXT NOT NULL,
  created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_review_receipt_created ON metadata_review_receipt (created_at);

CREATE TABLE media_metadata_file (
  song_id VARCHAR(36) PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 1,
  synced_revision BIGINT NOT NULL DEFAULT 0,
  source_location VARCHAR(2048),
  result_json TEXT,
  error VARCHAR(200),
  updated_at VARCHAR(40)
);
CREATE INDEX idx_metadata_file_pending ON media_metadata_file(synced_revision,revision);


-- Song classification
CREATE TABLE classification_settings (
  id INTEGER PRIMARY KEY,
  enabled INTEGER NOT NULL DEFAULT 0,
  endpoint VARCHAR(2048) NOT NULL DEFAULT '',
  model VARCHAR(200) NOT NULL DEFAULT '',
  secret_encrypted TEXT,
  timeout_seconds INTEGER NOT NULL DEFAULT 60,
  interval_seconds INTEGER NOT NULL DEFAULT 3,
  revision BIGINT NOT NULL DEFAULT 0,
  next_request_at VARCHAR(40) NOT NULL DEFAULT ''
);
INSERT INTO classification_settings (id, enabled, endpoint, model, secret_encrypted, timeout_seconds, interval_seconds, revision, next_request_at) VALUES (1, 0, '', '', NULL, 60, 3, 0, '');

CREATE TABLE classification_tag (
  id VARCHAR(36) PRIMARY KEY,
  dimension VARCHAR(20) NOT NULL,
  name VARCHAR(80) NOT NULL,
  aliases_json TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  revision BIGINT NOT NULL DEFAULT 1,
  UNIQUE (dimension, name)
);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-0', 'LANGUAGE', '国语', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-1', 'LANGUAGE', '粤语', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-2', 'LANGUAGE', '英语', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-3', 'LANGUAGE', '日语', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-4', 'LANGUAGE', '韩语', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-5', 'LANGUAGE', '多语种', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('language-6', 'LANGUAGE', '其他', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-0', 'DECADE', '1950 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-1', 'DECADE', '1960 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-2', 'DECADE', '1970 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-3', 'DECADE', '1980 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-4', 'DECADE', '1990 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-5', 'DECADE', '2000 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-6', 'DECADE', '2010 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('decade-7', 'DECADE', '2020 年代', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-0', 'GENRE', '流行', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-1', 'GENRE', '摇滚', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-2', 'GENRE', '民谣', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-3', 'GENRE', '电子', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-4', 'GENRE', '说唱', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-5', 'GENRE', '爵士', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-6', 'GENRE', '古典', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('genre-7', 'GENRE', 'R&B', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('mood-0', 'MOOD', '欢快', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('mood-1', 'MOOD', '伤感', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('mood-2', 'MOOD', '温柔', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('mood-3', 'MOOD', '激昂', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('mood-4', 'MOOD', '平静', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('scene-0', 'SCENE', '聚会', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('scene-1', 'SCENE', '怀旧', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('scene-2', 'SCENE', '独唱', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('scene-3', 'SCENE', '合唱', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('custom-0', 'CUSTOM', '家庭常唱', '[]', 1, 1);
INSERT INTO classification_tag (id, dimension, name, aliases_json, active, revision) VALUES ('custom-1', 'CUSTOM', '长辈喜爱', '[]', 1, 1);

CREATE TABLE classification_song (
  song_id VARCHAR(36) PRIMARY KEY,
  revision BIGINT NOT NULL,
  state_json TEXT NOT NULL
);

CREATE TABLE classification_assignment (
  song_id VARCHAR(36) NOT NULL,
  dimension VARCHAR(20) NOT NULL,
  tag_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (song_id, dimension, tag_id)
);
CREATE INDEX idx_classification_tag_song ON classification_assignment (tag_id, song_id);
CREATE INDEX idx_classification_dimension_song ON classification_assignment (dimension, song_id);

CREATE TABLE classification_task (
  id VARCHAR(36) PRIMARY KEY,
  status VARCHAR(20) NOT NULL,
  config_json TEXT NOT NULL,
  vocabulary_json TEXT NOT NULL,
  dimensions_json TEXT NOT NULL,
  automatic_json TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  next_at VARCHAR(40) NOT NULL
);

CREATE TABLE classification_item (
  id VARCHAR(36) PRIMARY KEY,
  task_id VARCHAR(36) NOT NULL,
  song_id VARCHAR(36) NOT NULL,
  status VARCHAR(20) NOT NULL,
  input_json TEXT NOT NULL,
  input_version VARCHAR(64) NOT NULL,
  song_revision BIGINT NOT NULL,
  suggestion_json TEXT,
  error VARCHAR(1000),
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE (task_id, song_id)
);
CREATE INDEX idx_classification_task_items ON classification_item (task_id, status, id);

CREATE TABLE classification_history (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  task_id VARCHAR(36),
  origin VARCHAR(20) NOT NULL,
  before_json TEXT NOT NULL,
  after_json TEXT NOT NULL,
  after_revision BIGINT NOT NULL,
  reverted INTEGER NOT NULL DEFAULT 0,
  created_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_classification_history_song ON classification_history (song_id, created_at, id);

CREATE TABLE classification_receipt (
  id VARCHAR(36) PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  response_json TEXT NOT NULL
);

CREATE TABLE classification_tag_cover (
  tag_id VARCHAR(36) PRIMARY KEY,
  version VARCHAR(64) NOT NULL,
  content_type VARCHAR(40) NOT NULL,
  image_bytes BLOB NOT NULL,
  FOREIGN KEY (tag_id) REFERENCES classification_tag(id)
);


-- AI services and compute connections
CREATE TABLE ai_registry_lock (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL
);
INSERT INTO ai_registry_lock (id, revision) VALUES (1, 1);

CREATE TABLE ai_service (
  id VARCHAR(36) PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(100) NOT NULL,
  provider VARCHAR(40) NOT NULL,
  protocol VARCHAR(40) NOT NULL,
  endpoint VARCHAR(2048) NOT NULL,
  model VARCHAR(200) NOT NULL DEFAULT '',
  output_mode VARCHAR(20) NOT NULL DEFAULT 'JSON_SCHEMA',
  secret_encrypted TEXT,
  credential_purpose VARCHAR(100) NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  timeout_seconds INTEGER NOT NULL DEFAULT 60,
  max_results INTEGER NOT NULL DEFAULT 5,
  revision BIGINT NOT NULL DEFAULT 1
);

CREATE TABLE ai_feature (
  id VARCHAR(40) PRIMARY KEY,
  model_service_id VARCHAR(36),
  search_service_id VARCHAR(36),
  enabled INTEGER NOT NULL DEFAULT 0,
  interval_seconds INTEGER NOT NULL DEFAULT 3,
  revision BIGINT NOT NULL DEFAULT 1,
  display_mode VARCHAR(20) NOT NULL DEFAULT 'PET',
  pet_id VARCHAR(40) NOT NULL DEFAULT 'music-sprite'
);
INSERT INTO ai_feature (id, model_service_id, search_service_id, enabled, interval_seconds, revision, display_mode, pet_id) VALUES ('classification', NULL, NULL, 0, 3, 1, 'PET', 'music-sprite');
INSERT INTO ai_feature (id, model_service_id, search_service_id, enabled, interval_seconds, revision, display_mode, pet_id) VALUES ('default-search', NULL, NULL, 0, 3, 1, 'PET', 'music-sprite');
INSERT INTO ai_feature (id, model_service_id, search_service_id, enabled, interval_seconds, revision, display_mode, pet_id) VALUES ('assistant', NULL, NULL, 0, 3, 1, 'PET', 'music-sprite');

CREATE TABLE compute_node_settings (
  id INTEGER PRIMARY KEY,
  mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  endpoint VARCHAR(512) NOT NULL DEFAULT '',
  secret_encrypted TEXT,
  mappings_json TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO compute_node_settings (id, mode, endpoint, secret_encrypted, mappings_json, revision) VALUES (1, 'AUTO', '', NULL, '[]', 0);

CREATE TABLE compute_pairing_identity (
  id INTEGER PRIMARY KEY,
  public_key TEXT NOT NULL,
  private_key_encrypted TEXT NOT NULL
);


-- Artists and artwork
CREATE TABLE artist_registry_lock (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO artist_registry_lock (id, revision) VALUES (1, 0);

CREATE TABLE artist (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  aliases_json TEXT NOT NULL,
  artist_type VARCHAR(20) NOT NULL,
  biography TEXT NOT NULL,
  active INTEGER NOT NULL,
  revision BIGINT NOT NULL,
  creation_hash VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  name_key VARCHAR(64),
  fetch_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  fetch_attempts INTEGER NOT NULL DEFAULT 0,
  fetch_after VARCHAR(40) NOT NULL DEFAULT '',
  fetch_generation BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX idx_artist_name_key ON artist(name_key);

CREATE TABLE artist_image (
  artist_id VARCHAR(36) NOT NULL,
  image_kind VARCHAR(16) NOT NULL,
  version VARCHAR(64) NOT NULL,
  image_bytes BLOB NOT NULL,
  origin VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
  provider VARCHAR(20),
  external_id VARCHAR(120),
  source_url TEXT,
  attribution TEXT,
  PRIMARY KEY (artist_id,image_kind),
  FOREIGN KEY (artist_id) REFERENCES artist(id)
);

CREATE TABLE artist_credit_binding (
  song_id VARCHAR(36) NOT NULL,
  credit_name VARCHAR(500) NOT NULL,
  artist_id VARCHAR(36),
  revision BIGINT NOT NULL,
  PRIMARY KEY (song_id,credit_name),
  FOREIGN KEY (artist_id) REFERENCES artist(id)
);
CREATE INDEX idx_artist_credit_artist ON artist_credit_binding(artist_id,song_id);

CREATE TABLE song_artist_override (
  source_id VARCHAR(36) NOT NULL,
  path_key VARCHAR(64) NOT NULL,
  artists_json TEXT,
  revision BIGINT NOT NULL,
  PRIMARY KEY(source_id,path_key)
);

CREATE TABLE artist_artwork_settings (
  id INTEGER PRIMARY KEY,
  fanart_key_encrypted TEXT
);
INSERT INTO artist_artwork_settings (id, fanart_key_encrypted) VALUES (1, NULL);

CREATE TABLE artist_image_fetch (
  artist_id VARCHAR(36) NOT NULL,
  image_kind VARCHAR(16) NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INTEGER NOT NULL DEFAULT 0,
  fetch_after VARCHAR(40) NOT NULL DEFAULT '',
  generation BIGINT NOT NULL DEFAULT 0,
  candidates_json TEXT,
  PRIMARY KEY(artist_id,image_kind)
);

CREATE TABLE artist_image_policy (
  artist_id VARCHAR(36) NOT NULL,
  image_kind VARCHAR(16) NOT NULL,
  blocked INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(artist_id,image_kind)
);


-- TV, party sessions and playlists
CREATE TABLE tv_record (
  id VARCHAR(64) PRIMARY KEY,
  kind VARCHAR(24) NOT NULL,
  secret_hash VARCHAR(64),
  document TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX tv_record_secret ON tv_record(secret_hash);
CREATE INDEX tv_record_kind ON tv_record(kind,id);

CREATE TABLE tv_home_settings (
  id INTEGER PRIMARY KEY,
  headline VARCHAR(80) NOT NULL,
  subtitle VARCHAR(160) NOT NULL,
  auto_rotate INTEGER NOT NULL DEFAULT 1,
  rotation_seconds INTEGER NOT NULL DEFAULT 8,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO tv_home_settings (id, headline, subtitle, auto_rotate, rotation_seconds, revision) VALUES (1, '把生活，唱成热爱。', '你的私人音乐现场', 1, 8, 0);

CREATE TABLE tv_home_banner (
  id VARCHAR(64) PRIMARY KEY,
  label VARCHAR(80) NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1,
  sort_order INTEGER NOT NULL,
  version VARCHAR(64) NOT NULL,
  image_bytes BLOB NOT NULL
);

CREATE TABLE playlist (
  id VARCHAR(36) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  song_ids_json TEXT NOT NULL,
  revision BIGINT NOT NULL,
  creation_hash VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);

CREATE TABLE playlist_lock (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO playlist_lock (id, revision) VALUES (1, 0);


-- Transcoding and audio processing
CREATE TABLE media_transcode (
  id VARCHAR(36) PRIMARY KEY,
  execution_id VARCHAR(36) NOT NULL,
  pipeline_version VARCHAR(16) NOT NULL DEFAULT '1',
  song_id VARCHAR(64) NOT NULL,
  owner_id VARCHAR(100) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  state VARCHAR(24) NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  node_origin VARCHAR(512) NOT NULL,
  input_json TEXT NOT NULL,
  target_json TEXT NOT NULL,
  result_json TEXT NOT NULL,
  error TEXT NOT NULL,
  renewed_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX media_transcode_pending ON media_transcode(purpose,state,created_at);
CREATE INDEX media_transcode_song ON media_transcode(song_id,fingerprint,state);

CREATE TABLE media_audio_role (
  song_id VARCHAR(36) PRIMARY KEY,
  fingerprint VARCHAR(128) NOT NULL,
  original_id VARCHAR(16),
  backing_id VARCHAR(16),
  origin VARCHAR(16) NOT NULL,
  revision BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);

CREATE TABLE media_audio_job (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  node_origin VARCHAR(2048) NOT NULL,
  result_json TEXT NOT NULL,
  error VARCHAR(200) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  backend VARCHAR(16) NOT NULL DEFAULT ''
);
CREATE INDEX media_audio_job_pending ON media_audio_job(state,created_at);
CREATE INDEX media_audio_job_song ON media_audio_job(song_id,kind,fingerprint);

CREATE TABLE media_audio_import (
  song_id VARCHAR(36) PRIMARY KEY,
  claim_json TEXT NOT NULL
);

CREATE TABLE audio_batch (
  id VARCHAR(36) PRIMARY KEY,
  state VARCHAR(16) NOT NULL,
  backend VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  selection_total BIGINT NOT NULL DEFAULT 0,
  prepared_count BIGINT NOT NULL DEFAULT 0,
  preparation_error VARCHAR(200) NOT NULL DEFAULT ''
);

CREATE TABLE audio_batch_item (
  batch_id VARCHAR(36) NOT NULL,
  song_id VARCHAR(36) NOT NULL,
  fingerprint VARCHAR(128) NOT NULL,
  title VARCHAR(2048) NOT NULL,
  category VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL,
  job_id VARCHAR(36),
  error VARCHAR(200) NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY(batch_id,song_id)
);
CREATE INDEX audio_batch_item_pending ON audio_batch_item(state,updated_at);


-- Song cache
CREATE TABLE song_cache_settings (
  id INTEGER PRIMARY KEY,
  max_bytes BIGINT NOT NULL DEFAULT 0,
  policy VARCHAR(16) NOT NULL DEFAULT 'HYBRID',
  ttl_hours INTEGER NOT NULL DEFAULT 168,
  revision BIGINT NOT NULL DEFAULT 0,
  high_watermark_percent INTEGER NOT NULL DEFAULT 90,
  low_watermark_percent INTEGER NOT NULL DEFAULT 75
);
INSERT INTO song_cache_settings (id, max_bytes, policy, ttl_hours, revision, high_watermark_percent, low_watermark_percent) VALUES (1, 0, 'HYBRID', 168, 0, 90, 75);

CREATE TABLE song_cache_entry (
  id VARCHAR(36) PRIMARY KEY,
  cache_key VARCHAR(64) NOT NULL UNIQUE,
  song_id VARCHAR(64) NOT NULL,
  execution_json TEXT NOT NULL,
  target_json TEXT NOT NULL,
  artifacts_json TEXT NOT NULL,
  media_type VARCHAR(16) NOT NULL,
  bytes BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  last_access_at BIGINT NOT NULL,
  hits BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  delete_token VARCHAR(36),
  delete_retry_at BIGINT NOT NULL DEFAULT 0,
  delete_error VARCHAR(200) NOT NULL DEFAULT '',
  residue_bytes BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX song_cache_song_state ON song_cache_entry(song_id,state);
CREATE INDEX song_cache_page ON song_cache_entry(last_access_at DESC,id);
CREATE INDEX song_cache_delete_retry ON song_cache_entry(state,delete_retry_at);

CREATE TABLE song_cache_lease (
  session_id VARCHAR(36) PRIMARY KEY,
  cache_id VARCHAR(36) NOT NULL REFERENCES song_cache_entry(id) ON DELETE CASCADE,
  renewed_at BIGINT NOT NULL
);
CREATE INDEX song_cache_lease_active ON song_cache_lease(cache_id,renewed_at);

CREATE TABLE song_cache_cleanup_job (
  id VARCHAR(36) PRIMARY KEY,
  mode VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  result_json TEXT NOT NULL
);
CREATE INDEX song_cache_job_recent ON song_cache_cleanup_job(created_at,id);


-- Playback history and actual-playback accounting
CREATE TABLE playback_history (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(64) NOT NULL,
  title VARCHAR(500) NOT NULL,
  artists_json TEXT NOT NULL DEFAULT '[]',
  version_label VARCHAR(200) NOT NULL DEFAULT '',
  source_name VARCHAR(200) NOT NULL DEFAULT '',
  client_type VARCHAR(16) NOT NULL CHECK(client_type IN ('TV','MOBILE','ADMIN')),
  device_name VARCHAR(200) NOT NULL DEFAULT '',
  requested_by VARCHAR(200) NOT NULL DEFAULT '',
  session_id VARCHAR(64),
  attempt_id VARCHAR(64),
  started_at BIGINT NOT NULL,
  ended_at BIGINT,
  played_ms BIGINT NOT NULL DEFAULT 0 CHECK(played_ms >= 0),
  duration_ms BIGINT CHECK(duration_ms IS NULL OR duration_ms > 0),
  status VARCHAR(16) NOT NULL CHECK(status IN ('PLAYING','COMPLETED','SKIPPED','STOPPED','ERROR','INTERRUPTED')),
  effective_at BIGINT,
  heat_applied INTEGER NOT NULL DEFAULT 0 CHECK(heat_applied IN (0,1)),
  activity_prepared INTEGER NOT NULL DEFAULT 0 CHECK(activity_prepared IN (0,1)),
  last_activity_at BIGINT,
  CHECK(ended_at IS NULL OR ended_at >= started_at),
  UNIQUE(client_type,attempt_id)
);
CREATE INDEX playback_history_recent ON playback_history(started_at,id);
CREATE INDEX playback_history_song_recent ON playback_history(song_id,started_at,id);
CREATE INDEX playback_history_effective ON playback_history(effective_at,song_id);
CREATE INDEX playback_history_song_effective ON playback_history(song_id,effective_at);
CREATE INDEX playback_history_aggregation ON playback_history(heat_applied,activity_prepared,effective_at,started_at,id);
CREATE INDEX playback_history_preparation ON playback_history(activity_prepared,started_at,id);

CREATE TABLE song_playback_profile (
  song_id VARCHAR(64) PRIMARY KEY,
  last_played_at BIGINT,
  heat DOUBLE NOT NULL DEFAULT 0 CHECK(heat >= 0 AND heat <= 1),
  heat_updated_at BIGINT,
  pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
  pin_revision BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE playback_activity_state (
  id INTEGER PRIMARY KEY CHECK(id=1),
  ready INTEGER NOT NULL DEFAULT 1 CHECK(ready IN (0,1)),
  phase VARCHAR(16) NOT NULL DEFAULT 'READY',
  cursor_started_at BIGINT NOT NULL DEFAULT 0,
  cursor_id VARCHAR(36) NOT NULL DEFAULT ''
);
INSERT INTO playback_activity_state (id, ready, phase, cursor_started_at, cursor_id) VALUES (1, 1, 'READY', 0, '');

CREATE TABLE playback_attempt (
  id VARCHAR(36) PRIMARY KEY,
  client_type VARCHAR(16) NOT NULL CHECK(client_type IN ('TV','MOBILE','ADMIN')),
  owner_session_id VARCHAR(64) NOT NULL,
  token_hash VARCHAR(64),
  song_id VARCHAR(64) NOT NULL,
  snapshot_json TEXT NOT NULL,
  event_seq BIGINT NOT NULL DEFAULT -1,
  actual_played_ms BIGINT NOT NULL DEFAULT 0 CHECK(actual_played_ms >= 0),
  elapsed_ms BIGINT NOT NULL DEFAULT 0 CHECK(elapsed_ms >= 0),
  state VARCHAR(16) NOT NULL CHECK(state IN ('PREPARING','BUFFERING','PLAYING','PAUSED','COMPLETED','STOPPED','SKIPPED','ERROR','INTERRUPTED')),
  started_at BIGINT NOT NULL,
  client_started_at BIGINT,
  client_last_activity_at BIGINT,
  started_playing_at BIGINT,
  last_activity_at BIGINT,
  last_event_at BIGINT NOT NULL,
  lease_until BIGINT NOT NULL,
  history_id VARCHAR(36),
  effective_at BIGINT,
  duration_ms BIGINT
);
CREATE INDEX playback_attempt_song_lease ON playback_attempt(song_id,state,lease_until);
CREATE INDEX playback_attempt_expiry ON playback_attempt(state,lease_until,id);
CREATE INDEX playback_attempt_owner ON playback_attempt(client_type,owner_session_id,state);


-- Missing-song feedback
CREATE TABLE missing_song_feedback (
  id VARCHAR(36) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  artist VARCHAR(200) NOT NULL,
  version_label VARCHAR(120) NOT NULL,
  description TEXT NOT NULL,
  submitted_by VARCHAR(80) NOT NULL,
  source VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  handling_note TEXT NOT NULL,
  resolved_song_id VARCHAR(64),
  resolved_song_title TEXT,
  resolved_song_artists_json TEXT NOT NULL,
  resolved_at VARCHAR(40),
  search_text TEXT NOT NULL,
  creation_hash VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX missing_song_feedback_created ON missing_song_feedback(created_at DESC, id);
CREATE INDEX missing_song_feedback_status_created ON missing_song_feedback(status, created_at DESC, id);

CREATE TABLE missing_song_feedback_lock (
  id INTEGER PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO missing_song_feedback_lock (id, revision) VALUES (1, 0);


-- WebDAV metadata and shared metadata jobs
CREATE TABLE webdav_metadata_source (
  source_id VARCHAR(36) PRIMARY KEY,
  binding VARCHAR(64) NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 0,
  paused INTEGER NOT NULL DEFAULT 0,
  access_group VARCHAR(64) NOT NULL,
  private_origins TEXT NOT NULL,
  blocked_until VARCHAR(30),
  blocked_reason VARCHAR(100),
  empty_scans INTEGER NOT NULL DEFAULT 0,
  empty_since VARCHAR(30),
  last_empty_scan VARCHAR(36)
);

CREATE TABLE webdav_resource (
  song_id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  binding VARCHAR(64) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  path_hash VARCHAR(64) NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  generation BIGINT NOT NULL DEFAULT 1,
  last_seen_at VARCHAR(30) NOT NULL,
  last_scan_id VARCHAR(36),
  missing_since VARCHAR(30),
  missing_scans INTEGER NOT NULL DEFAULT 0,
  archived_at VARCHAR(30),
  identity_size BIGINT,
  identity_modified VARCHAR(30),
  file_size BIGINT,
  modified_at VARCHAR(30),
  etag VARCHAR(512),
  changed INTEGER NOT NULL DEFAULT 0,
  state VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  result_json TEXT,
  tags_json TEXT,
  extractor_version VARCHAR(40),
  updated_at VARCHAR(30),
  bytes_read BIGINT NOT NULL DEFAULT 0,
  request_count INTEGER NOT NULL DEFAULT 0,
  elapsed_ms BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_webdav_resource_source ON webdav_resource(source_id, archived_at, song_id);

CREATE TABLE webdav_metadata_task (
  id VARCHAR(36) PRIMARY KEY,
  song_id VARCHAR(36) NOT NULL,
  source_id VARCHAR(36) NOT NULL,
  binding VARCHAR(64) NOT NULL,
  generation BIGINT NOT NULL,
  deep INTEGER NOT NULL DEFAULT 0,
  state VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  attempts INTEGER NOT NULL DEFAULT 0,
  available_at VARCHAR(30) NOT NULL,
  created_at VARCHAR(30) NOT NULL,
  error VARCHAR(100),
  manual INTEGER NOT NULL DEFAULT 0,
  UNIQUE(song_id, binding, generation)
);
CREATE INDEX idx_webdav_metadata_pending ON webdav_metadata_task(state, available_at);

CREATE TABLE webdav_access_budget (
  access_group VARCHAR(64) PRIMARY KEY,
  day_key VARCHAR(10) NOT NULL,
  files_started INTEGER NOT NULL DEFAULT 0,
  bytes_read BIGINT NOT NULL DEFAULT 0,
  next_request_at VARCHAR(30),
  next_file_at VARCHAR(30),
  blocked_until VARCHAR(30)
);

CREATE TABLE webdav_share_lock (
  id INTEGER PRIMARY KEY
);
INSERT INTO webdav_share_lock (id) VALUES (1);

CREATE TABLE webdav_share_job (
  id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(36) NOT NULL,
  binding VARCHAR(64) NOT NULL,
  state VARCHAR(20) NOT NULL,
  base_id VARCHAR(36),
  cursor_id VARCHAR(36) NOT NULL DEFAULT '',
  total_count BIGINT NOT NULL DEFAULT 0,
  processed_count BIGINT NOT NULL DEFAULT 0,
  part_count INTEGER NOT NULL DEFAULT 0,
  error VARCHAR(200),
  created_at VARCHAR(30) NOT NULL
);
CREATE INDEX idx_share_job_source ON webdav_share_job(source_id,created_at);

CREATE TABLE webdav_share_entry (
  job_id VARCHAR(36) NOT NULL,
  song_id VARCHAR(36) NOT NULL,
  relative_path VARCHAR(2048) NOT NULL,
  file_size BIGINT,
  result_json TEXT,
  filename_json TEXT NOT NULL,
  PRIMARY KEY(job_id,song_id)
);

CREATE TABLE webdav_share_asset (
  job_id VARCHAR(36) NOT NULL,
  hash VARCHAR(64) NOT NULL,
  PRIMARY KEY(job_id,hash)
);

CREATE TABLE webdav_share_receipt (
  source_id VARCHAR(36) NOT NULL,
  binding VARCHAR(64) NOT NULL,
  hash VARCHAR(64) NOT NULL,
  processed INTEGER NOT NULL DEFAULT 0,
  imported INTEGER NOT NULL DEFAULT 0,
  skipped INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(source_id,binding,hash)
);

-- migrate:down
-- Drop dependent tables before their referenced tables.
DROP TABLE webdav_share_receipt;
DROP TABLE webdav_share_asset;
DROP TABLE webdav_share_entry;
DROP TABLE webdav_share_job;
DROP TABLE webdav_share_lock;
DROP TABLE webdav_access_budget;
DROP TABLE webdav_metadata_task;
DROP TABLE webdav_resource;
DROP TABLE webdav_metadata_source;
DROP TABLE missing_song_feedback_lock;
DROP TABLE missing_song_feedback;
DROP TABLE playback_attempt;
DROP TABLE playback_activity_state;
DROP TABLE song_playback_profile;
DROP TABLE playback_history;
DROP TABLE song_cache_cleanup_job;
DROP TABLE song_cache_lease;
DROP TABLE song_cache_entry;
DROP TABLE song_cache_settings;
DROP TABLE audio_batch_item;
DROP TABLE audio_batch;
DROP TABLE media_audio_import;
DROP TABLE media_audio_job;
DROP TABLE media_audio_role;
DROP TABLE media_transcode;
DROP TABLE playlist_lock;
DROP TABLE playlist;
DROP TABLE tv_home_banner;
DROP TABLE tv_home_settings;
DROP TABLE tv_record;
DROP TABLE artist_image_policy;
DROP TABLE artist_image_fetch;
DROP TABLE artist_artwork_settings;
DROP TABLE song_artist_override;
DROP TABLE artist_credit_binding;
DROP TABLE artist_image;
DROP TABLE artist;
DROP TABLE artist_registry_lock;
DROP TABLE compute_pairing_identity;
DROP TABLE compute_node_settings;
DROP TABLE ai_feature;
DROP TABLE ai_service;
DROP TABLE ai_registry_lock;
DROP TABLE classification_tag_cover;
DROP TABLE classification_receipt;
DROP TABLE classification_history;
DROP TABLE classification_item;
DROP TABLE classification_task;
DROP TABLE classification_assignment;
DROP TABLE classification_song;
DROP TABLE classification_tag;
DROP TABLE classification_settings;
DROP TABLE media_metadata_file;
DROP TABLE metadata_review_receipt;
DROP TABLE metadata_cover_asset;
DROP TABLE song_metadata;
DROP TABLE metadata_candidate;
DROP TABLE metadata_item;
DROP TABLE metadata_batch;
DROP TABLE metadata_settings;
DROP TABLE media_scan_song;
DROP TABLE media_scan_directory;
DROP TABLE media_scan_task;
DROP TABLE media_song;
DROP TABLE filename_parser_config;
DROP TABLE media_source;
DROP TABLE auth_event;
DROP TABLE auth_throttle;
DROP TABLE auth_recovery;
DROP TABLE auth_token;
DROP TABLE auth_account;
