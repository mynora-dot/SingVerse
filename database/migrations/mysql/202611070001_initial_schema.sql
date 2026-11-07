-- migrate:up
-- Initial schema for a fresh deployment; runtime defaults and seed data are defined directly.

CREATE TABLE `auth_account` (
  `id` int NOT NULL,
  `username` varchar(32) NOT NULL,
  `password_hash` varchar(256) NOT NULL,
  `default_password` int NOT NULL,
  `mfa_secret` varchar(512) DEFAULT NULL,
  `pending_secret` varchar(512) DEFAULT NULL,
  `pending_attempts` int NOT NULL DEFAULT '0',
  `pending_expires` bigint NOT NULL DEFAULT '0',
  `last_totp_step` bigint NOT NULL DEFAULT '-1',
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
);

CREATE TABLE `auth_token` (
  `token_hash` varchar(64) NOT NULL,
  `kind` varchar(16) NOT NULL,
  `csrf` varchar(64) NOT NULL,
  `created_at` bigint NOT NULL,
  `last_seen` bigint NOT NULL,
  `expires_at` bigint NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `revision` bigint NOT NULL,
  PRIMARY KEY (`token_hash`)
);

CREATE TABLE `auth_recovery` (
  `code_hash` varchar(64) NOT NULL,
  PRIMARY KEY (`code_hash`)
);

CREATE TABLE `auth_throttle` (
  `bucket` varchar(128) NOT NULL,
  `failures` int NOT NULL,
  `blocked_until` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`bucket`)
);

CREATE TABLE `auth_event` (
  `id` varchar(36) NOT NULL,
  `event_type` varchar(48) NOT NULL,
  `occurred_at` bigint NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `media_source` (
  `id` varchar(36) NOT NULL,
  `name` varchar(100) NOT NULL,
  `source_type` varchar(16) NOT NULL,
  `location` varchar(2048) NOT NULL,
  `location_hash` varchar(64) NOT NULL,
  `username` varchar(256) NOT NULL,
  `password_encrypted` text,
  `created_at` varchar(30) NOT NULL,
  `updated_at` varchar(30) NOT NULL,
  `parser_profile_id` varchar(36) NOT NULL DEFAULT 'default',
  `scan_enabled` int NOT NULL DEFAULT '0',
  `scan_cron` varchar(120) DEFAULT NULL,
  `next_scan_at` varchar(30) DEFAULT NULL,
  `last_scan_at` varchar(30) DEFAULT NULL,
  `last_scan_status` varchar(24) DEFAULT NULL,
  `last_scan_error` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `location_hash` (`location_hash`),
  KEY `idx_media_source_parser_profile` (`parser_profile_id`),
  KEY `idx_source_created_page` (`created_at` DESC,`id`),
  KEY `idx_source_type_page` (`source_type`,`created_at` DESC,`id`),
  KEY `idx_source_profile_page` (`parser_profile_id`,`created_at` DESC,`id`),
  KEY `idx_source_scan_due` (`scan_enabled`,`next_scan_at`,`id`)
);

CREATE TABLE `filename_parser_config` (
  `id` int NOT NULL,
  `revision` bigint NOT NULL,
  `rules_json` mediumtext NOT NULL,
  `updated_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `filename_parser_config_chk_1` CHECK ((`id` = 1))
);

CREATE TABLE `media_song` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `relative_path` varchar(2048) NOT NULL,
  `path_hash` varchar(64) NOT NULL,
  `file_size` bigint DEFAULT NULL,
  `modified_at` varchar(30) DEFAULT NULL,
  `scanned_at` varchar(30) NOT NULL,
  `parser_profile_id` varchar(36) NOT NULL,
  `parser_revision` bigint NOT NULL,
  `selected_rule_id` varchar(100) DEFAULT NULL,
  `result_json` text NOT NULL,
  `needs_review` int NOT NULL DEFAULT '1',
  `embedded_tags_json` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `source_id` (`source_id`,`path_hash`),
  KEY `idx_media_song_source` (`source_id`,`id`),
  KEY `idx_media_song_review` (`needs_review`,`id`),
  KEY `idx_song_scanned_page` (`scanned_at` DESC,`id`),
  KEY `idx_song_source_page` (`source_id`,`scanned_at` DESC,`id`),
  KEY `idx_song_review_page` (`needs_review`,`scanned_at` DESC,`id`),
  KEY `idx_song_source_review_page` (`source_id`,`needs_review`,`scanned_at` DESC,`id`)
);

CREATE TABLE `metadata_settings` (
  `id` int NOT NULL,
  `enabled` int NOT NULL DEFAULT '0',
  `providers_json` text NOT NULL,
  `auto_apply` int NOT NULL DEFAULT '0',
  `threshold` double NOT NULL DEFAULT '0.95',
  `updated_at` varchar(30) NOT NULL,
  `min_interval_seconds` int NOT NULL DEFAULT '3',
  `max_interval_seconds` int NOT NULL DEFAULT '8',
  PRIMARY KEY (`id`),
  CONSTRAINT `metadata_settings_chk_1` CHECK ((`id` = 1))
);
INSERT INTO metadata_settings (id, providers_json, updated_at) VALUES (1, '["NETEASE"]', '2026-10-28T00:00:00Z');

CREATE TABLE `metadata_batch` (
  `id` varchar(36) NOT NULL,
  `mode` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL,
  `providers_json` text NOT NULL,
  `auto_apply` int NOT NULL,
  `threshold` double NOT NULL,
  `skipped_existing` int NOT NULL DEFAULT '0',
  `created_at` varchar(30) NOT NULL,
  `updated_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_metadata_batch_created` (`created_at`,`id`)
);

CREATE TABLE `metadata_item` (
  `id` varchar(36) NOT NULL,
  `batch_id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `fingerprint` varchar(64) NOT NULL,
  `title` varchar(1024) DEFAULT NULL,
  `artists_json` text NOT NULL,
  `status` varchar(24) NOT NULL,
  `candidates_json` mediumtext,
  `error_message` varchar(500) DEFAULT NULL,
  `updated_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_metadata_item_batch` (`batch_id`,`status`,`id`)
);

CREATE TABLE `metadata_candidate` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `fingerprint` varchar(64) NOT NULL,
  `track_json` mediumtext NOT NULL,
  `score` double NOT NULL,
  `fetched_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_metadata_candidate_song` (`song_id`,`fetched_at`)
);

CREATE TABLE `song_metadata` (
  `song_id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `fingerprint` varchar(64) NOT NULL,
  `track_json` mediumtext NOT NULL,
  `fields_json` text NOT NULL,
  `origins_json` text NOT NULL,
  `applied_at` varchar(30) NOT NULL,
  PRIMARY KEY (`song_id`),
  KEY `idx_song_metadata_source` (`source_id`)
);

CREATE TABLE `media_scan_task` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `active_source` varchar(36) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `phase` varchar(20) NOT NULL,
  `directories` bigint NOT NULL DEFAULT '0',
  `entries` bigint NOT NULL DEFAULT '0',
  `total_songs` int NOT NULL DEFAULT '0',
  `matched_songs` int NOT NULL DEFAULT '0',
  `review_songs` int NOT NULL DEFAULT '0',
  `skipped_files` bigint NOT NULL DEFAULT '0',
  `cancel_requested` int NOT NULL DEFAULT '0',
  `error` varchar(500) DEFAULT NULL,
  `created_at` varchar(30) NOT NULL,
  `started_at` varchar(30) DEFAULT NULL,
  `finished_at` varchar(30) DEFAULT NULL,
  `added_songs` int NOT NULL DEFAULT '0',
  `reused_songs` int NOT NULL DEFAULT '0',
  `missing_songs` int NOT NULL DEFAULT '0',
  `removed_songs` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `active_source` (`active_source`),
  KEY `idx_scan_task_source` (`source_id`,`created_at`,`id`)
);

CREATE TABLE `media_scan_directory` (
  `task_id` varchar(36) NOT NULL,
  `path_hash` varchar(64) NOT NULL,
  `path` varchar(4096) NOT NULL,
  `depth` int NOT NULL,
  `processed` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`task_id`,`path_hash`),
  KEY `idx_scan_directory_pending` (`task_id`,`processed`)
);

CREATE TABLE `media_scan_song` (
  `task_id` varchar(36) NOT NULL,
  `id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `relative_path` varchar(2048) NOT NULL,
  `path_hash` varchar(64) NOT NULL,
  `file_size` bigint DEFAULT NULL,
  `modified_at` varchar(30) DEFAULT NULL,
  `scanned_at` varchar(30) NOT NULL,
  `parser_profile_id` varchar(36) NOT NULL,
  `parser_revision` bigint NOT NULL,
  `selected_rule_id` varchar(100) DEFAULT NULL,
  `result_json` text NOT NULL,
  `needs_review` int NOT NULL,
  `etag` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`task_id`,`id`)
);

CREATE TABLE `metadata_cover_asset` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `fingerprint` varchar(64) NOT NULL,
  `mime` varchar(32) NOT NULL,
  `width` int NOT NULL,
  `height` int NOT NULL,
  `created_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_cover_asset_created` (`created_at`,`id`)
);

CREATE TABLE `metadata_review_receipt` (
  `request_id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `response_json` mediumtext NOT NULL,
  `created_at` varchar(30) NOT NULL,
  PRIMARY KEY (`request_id`),
  KEY `idx_review_receipt_created` (`created_at`)
);

CREATE TABLE `classification_settings` (
  `id` int NOT NULL,
  `enabled` int NOT NULL DEFAULT '0',
  `endpoint` varchar(2048) NOT NULL DEFAULT '',
  `model` varchar(200) NOT NULL DEFAULT '',
  `secret_encrypted` text,
  `timeout_seconds` int NOT NULL DEFAULT '60',
  `interval_seconds` int NOT NULL DEFAULT '3',
  `revision` bigint NOT NULL DEFAULT '0',
  `next_request_at` varchar(40) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
);
INSERT INTO classification_settings (id) VALUES (1);

CREATE TABLE `classification_tag` (
  `id` varchar(36) NOT NULL,
  `dimension` varchar(20) NOT NULL,
  `name` varchar(80) NOT NULL,
  `aliases_json` text NOT NULL,
  `active` int NOT NULL DEFAULT '1',
  `revision` bigint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `dimension` (`dimension`,`name`)
);
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-0', 'LANGUAGE', '国语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-1', 'LANGUAGE', '粤语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-2', 'LANGUAGE', '英语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-3', 'LANGUAGE', '日语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-4', 'LANGUAGE', '韩语', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-5', 'LANGUAGE', '多语种', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('language-6', 'LANGUAGE', '其他', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-0', 'DECADE', '1950 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-1', 'DECADE', '1960 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-2', 'DECADE', '1970 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-3', 'DECADE', '1980 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-4', 'DECADE', '1990 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-5', 'DECADE', '2000 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-6', 'DECADE', '2010 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('decade-7', 'DECADE', '2020 年代', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-0', 'GENRE', '流行', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-1', 'GENRE', '摇滚', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-2', 'GENRE', '民谣', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-3', 'GENRE', '电子', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-4', 'GENRE', '说唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-5', 'GENRE', '爵士', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-6', 'GENRE', '古典', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('genre-7', 'GENRE', 'R&B', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-0', 'MOOD', '欢快', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-1', 'MOOD', '伤感', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-2', 'MOOD', '温柔', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-3', 'MOOD', '激昂', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('mood-4', 'MOOD', '平静', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-0', 'SCENE', '聚会', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-1', 'SCENE', '怀旧', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-2', 'SCENE', '独唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('scene-3', 'SCENE', '合唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('custom-0', 'CUSTOM', '家庭常唱', '[]');
INSERT INTO classification_tag (id, dimension, name, aliases_json) VALUES ('custom-1', 'CUSTOM', '长辈喜爱', '[]');

CREATE TABLE `classification_song` (
  `song_id` varchar(36) NOT NULL,
  `revision` bigint NOT NULL,
  `state_json` text NOT NULL,
  PRIMARY KEY (`song_id`)
);

CREATE TABLE `classification_assignment` (
  `song_id` varchar(36) NOT NULL,
  `dimension` varchar(20) NOT NULL,
  `tag_id` varchar(36) NOT NULL,
  PRIMARY KEY (`song_id`,`dimension`,`tag_id`),
  KEY `idx_classification_tag_song` (`tag_id`,`song_id`),
  KEY `idx_classification_dimension_song` (`dimension`,`song_id`)
);

CREATE TABLE `classification_task` (
  `id` varchar(36) NOT NULL,
  `status` varchar(20) NOT NULL,
  `config_json` text NOT NULL,
  `vocabulary_json` text NOT NULL,
  `dimensions_json` text NOT NULL,
  `automatic_json` text NOT NULL,
  `created_at` varchar(40) NOT NULL,
  `updated_at` varchar(40) NOT NULL,
  `next_at` varchar(40) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `classification_item` (
  `id` varchar(36) NOT NULL,
  `task_id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `status` varchar(20) NOT NULL,
  `input_json` text NOT NULL,
  `input_version` varchar(64) NOT NULL,
  `song_revision` bigint NOT NULL,
  `suggestion_json` text,
  `error` varchar(1000) DEFAULT NULL,
  `updated_at` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `task_id` (`task_id`,`song_id`),
  KEY `idx_classification_task_items` (`task_id`,`status`,`id`)
);

CREATE TABLE `classification_history` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `task_id` varchar(36) DEFAULT NULL,
  `origin` varchar(20) NOT NULL,
  `before_json` text NOT NULL,
  `after_json` text NOT NULL,
  `after_revision` bigint NOT NULL,
  `reverted` int NOT NULL DEFAULT '0',
  `created_at` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_classification_history_song` (`song_id`,`created_at`,`id`)
);

CREATE TABLE `classification_receipt` (
  `id` varchar(36) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `response_json` text NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `ai_registry_lock` (
  `id` int NOT NULL,
  `revision` bigint NOT NULL,
  PRIMARY KEY (`id`)
);
INSERT INTO ai_registry_lock(id,revision) VALUES(1,1);

CREATE TABLE `ai_service` (
  `id` varchar(36) NOT NULL,
  `kind` varchar(16) NOT NULL,
  `name` varchar(100) NOT NULL,
  `provider` varchar(40) NOT NULL,
  `protocol` varchar(40) NOT NULL,
  `endpoint` varchar(2048) NOT NULL,
  `model` varchar(200) NOT NULL DEFAULT '',
  `output_mode` varchar(20) NOT NULL DEFAULT 'JSON_SCHEMA',
  `secret_encrypted` text,
  `credential_purpose` varchar(100) NOT NULL,
  `enabled` int NOT NULL DEFAULT '1',
  `timeout_seconds` int NOT NULL DEFAULT '60',
  `max_results` int NOT NULL DEFAULT '5',
  `revision` bigint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`)
);

CREATE TABLE `ai_feature` (
  `id` varchar(40) NOT NULL,
  `model_service_id` varchar(36) DEFAULT NULL,
  `search_service_id` varchar(36) DEFAULT NULL,
  `enabled` int NOT NULL DEFAULT '0',
  `interval_seconds` int NOT NULL DEFAULT '3',
  `revision` bigint NOT NULL DEFAULT '1',
  `display_mode` varchar(20) NOT NULL DEFAULT 'PET',
  `pet_id` varchar(40) NOT NULL DEFAULT 'music-sprite',
  PRIMARY KEY (`id`)
);
INSERT INTO ai_feature(id,enabled,interval_seconds) VALUES('classification',0,3);
INSERT INTO ai_feature(id,enabled,interval_seconds) VALUES('default-search',0,3);
INSERT INTO ai_feature(id,enabled,interval_seconds,display_mode,pet_id) VALUES('assistant',0,3,'PET','music-sprite');

CREATE TABLE `compute_node_settings` (
  `id` int NOT NULL,
  `mode` varchar(16) NOT NULL DEFAULT 'AUTO',
  `endpoint` varchar(512) NOT NULL DEFAULT '',
  `secret_encrypted` text,
  `mappings_json` text NOT NULL,
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
);
INSERT INTO compute_node_settings(id,mode,endpoint,mappings_json) VALUES(1,'AUTO','','[]');

CREATE TABLE `tv_record` (
  `id` varchar(64) NOT NULL,
  `kind` varchar(24) NOT NULL,
  `secret_hash` varchar(64) DEFAULT NULL,
  `document` longtext NOT NULL,
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `tv_record_secret` (`secret_hash`),
  KEY `tv_record_kind` (`kind`,`id`)
);

CREATE TABLE `classification_tag_cover` (
  `tag_id` varchar(36) NOT NULL,
  `version` varchar(64) NOT NULL,
  `content_type` varchar(40) NOT NULL,
  `image_bytes` mediumblob NOT NULL,
  PRIMARY KEY (`tag_id`),
  CONSTRAINT `classification_tag_cover_ibfk_1` FOREIGN KEY (`tag_id`) REFERENCES `classification_tag` (`id`)
);

CREATE TABLE `artist_registry_lock` (
  `id` int NOT NULL,
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
);
INSERT INTO artist_registry_lock(id) VALUES (1);

CREATE TABLE `artist` (
  `id` varchar(36) NOT NULL,
  `name` varchar(500) NOT NULL,
  `aliases_json` text NOT NULL,
  `artist_type` varchar(20) NOT NULL,
  `biography` text NOT NULL,
  `active` int NOT NULL,
  `revision` bigint NOT NULL,
  `creation_hash` varchar(64) NOT NULL,
  `created_at` varchar(40) NOT NULL,
  `updated_at` varchar(40) NOT NULL,
  `name_key` varchar(64) DEFAULT NULL,
  `fetch_state` varchar(20) NOT NULL DEFAULT 'PENDING',
  `fetch_attempts` int NOT NULL DEFAULT '0',
  `fetch_after` varchar(40) NOT NULL DEFAULT '',
  `fetch_generation` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_artist_name_key` (`name_key`)
);

CREATE TABLE `artist_image` (
  `artist_id` varchar(36) NOT NULL,
  `image_kind` varchar(16) NOT NULL,
  `version` varchar(64) NOT NULL,
  `image_bytes` mediumblob NOT NULL,
  `origin` varchar(16) NOT NULL DEFAULT 'UPLOAD',
  `provider` varchar(20) DEFAULT NULL,
  `external_id` varchar(120) DEFAULT NULL,
  `source_url` text,
  `attribution` text,
  PRIMARY KEY (`artist_id`,`image_kind`),
  CONSTRAINT `artist_image_ibfk_1` FOREIGN KEY (`artist_id`) REFERENCES `artist` (`id`)
);

CREATE TABLE `artist_credit_binding` (
  `song_id` varchar(36) NOT NULL,
  `credit_name` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `artist_id` varchar(36) DEFAULT NULL,
  `revision` bigint NOT NULL,
  PRIMARY KEY (`song_id`,`credit_name`),
  KEY `idx_artist_credit_artist` (`artist_id`,`song_id`),
  CONSTRAINT `artist_credit_binding_ibfk_1` FOREIGN KEY (`artist_id`) REFERENCES `artist` (`id`)
);

CREATE TABLE `artist_image_policy` (
  `artist_id` varchar(36) NOT NULL,
  `image_kind` varchar(16) NOT NULL,
  `blocked` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`artist_id`,`image_kind`)
);

CREATE TABLE `song_artist_override` (
  `source_id` varchar(36) NOT NULL,
  `path_key` varchar(64) NOT NULL,
  `artists_json` text,
  `revision` bigint NOT NULL,
  PRIMARY KEY (`source_id`,`path_key`)
);

CREATE TABLE `playlist` (
  `id` varchar(36) NOT NULL,
  `name` varchar(120) NOT NULL,
  `description` text NOT NULL,
  `song_ids_json` text NOT NULL,
  `revision` bigint NOT NULL,
  `creation_hash` varchar(64) NOT NULL,
  `created_at` varchar(40) NOT NULL,
  `updated_at` varchar(40) NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `playlist_lock` (
  `id` int NOT NULL,
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
);
INSERT INTO playlist_lock(id) VALUES(1);

CREATE TABLE `media_transcode` (
  `id` varchar(36) NOT NULL,
  `execution_id` varchar(36) NOT NULL,
  `pipeline_version` varchar(16) NOT NULL DEFAULT '1',
  `song_id` varchar(64) NOT NULL,
  `owner_id` varchar(100) NOT NULL,
  `purpose` varchar(16) NOT NULL,
  `state` varchar(24) NOT NULL,
  `fingerprint` varchar(128) NOT NULL,
  `node_origin` varchar(512) NOT NULL,
  `input_json` text NOT NULL,
  `target_json` text NOT NULL,
  `result_json` text NOT NULL,
  `error` text NOT NULL,
  `renewed_at` bigint NOT NULL,
  `created_at` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `media_transcode_pending` (`purpose`,`state`,`created_at`),
  KEY `media_transcode_song` (`song_id`,`fingerprint`,`state`)
);

CREATE TABLE `webdav_metadata_source` (
  `source_id` varchar(36) NOT NULL,
  `binding` varchar(64) NOT NULL,
  `enabled` int NOT NULL DEFAULT '0',
  `paused` int NOT NULL DEFAULT '0',
  `access_group` varchar(64) NOT NULL,
  `private_origins` text NOT NULL,
  `blocked_until` varchar(30) DEFAULT NULL,
  `blocked_reason` varchar(100) DEFAULT NULL,
  `empty_scans` int NOT NULL DEFAULT '0',
  `empty_since` varchar(30) DEFAULT NULL,
  `last_empty_scan` varchar(36) DEFAULT NULL,
  PRIMARY KEY (`source_id`)
);

CREATE TABLE `webdav_resource` (
  `song_id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `binding` varchar(64) NOT NULL,
  `relative_path` varchar(2048) NOT NULL,
  `path_hash` varchar(64) NOT NULL,
  `task_id` varchar(36) NOT NULL,
  `generation` bigint NOT NULL DEFAULT '1',
  `last_seen_at` varchar(30) NOT NULL,
  `last_scan_id` varchar(36) DEFAULT NULL,
  `missing_since` varchar(30) DEFAULT NULL,
  `missing_scans` int NOT NULL DEFAULT '0',
  `archived_at` varchar(30) DEFAULT NULL,
  `identity_size` bigint DEFAULT NULL,
  `identity_modified` varchar(30) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `modified_at` varchar(30) DEFAULT NULL,
  `etag` varchar(512) DEFAULT NULL,
  `changed` int NOT NULL DEFAULT '0',
  `state` varchar(30) NOT NULL DEFAULT 'PENDING',
  `result_json` text,
  `tags_json` text,
  `extractor_version` varchar(40) DEFAULT NULL,
  `updated_at` varchar(30) DEFAULT NULL,
  `bytes_read` bigint NOT NULL DEFAULT '0',
  `request_count` int NOT NULL DEFAULT '0',
  `elapsed_ms` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`song_id`),
  KEY `idx_webdav_resource_source` (`source_id`,`archived_at`,`song_id`)
);

CREATE TABLE `webdav_metadata_task` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `binding` varchar(64) NOT NULL,
  `generation` bigint NOT NULL,
  `deep` int NOT NULL DEFAULT '0',
  `state` varchar(30) NOT NULL DEFAULT 'QUEUED',
  `attempts` int NOT NULL DEFAULT '0',
  `available_at` varchar(30) NOT NULL,
  `created_at` varchar(30) NOT NULL,
  `error` varchar(100) DEFAULT NULL,
  `manual` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `song_id` (`song_id`,`binding`,`generation`),
  KEY `idx_webdav_metadata_pending` (`state`,`available_at`)
);

CREATE TABLE `webdav_access_budget` (
  `access_group` varchar(64) NOT NULL,
  `day_key` varchar(10) NOT NULL,
  `files_started` int NOT NULL DEFAULT '0',
  `bytes_read` bigint NOT NULL DEFAULT '0',
  `next_request_at` varchar(30) DEFAULT NULL,
  `next_file_at` varchar(30) DEFAULT NULL,
  `blocked_until` varchar(30) DEFAULT NULL,
  PRIMARY KEY (`access_group`)
);

CREATE TABLE `webdav_share_lock` (
  `id` int NOT NULL,
  PRIMARY KEY (`id`)
);
INSERT INTO webdav_share_lock(id) VALUES(1);

CREATE TABLE `webdav_share_job` (
  `id` varchar(36) NOT NULL,
  `source_id` varchar(36) NOT NULL,
  `binding` varchar(64) NOT NULL,
  `state` varchar(20) NOT NULL,
  `base_id` varchar(36) DEFAULT NULL,
  `cursor_id` varchar(36) NOT NULL DEFAULT '',
  `total_count` bigint NOT NULL DEFAULT '0',
  `processed_count` bigint NOT NULL DEFAULT '0',
  `part_count` int NOT NULL DEFAULT '0',
  `error` varchar(200) DEFAULT NULL,
  `created_at` varchar(30) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_share_job_source` (`source_id`,`created_at`)
);

CREATE TABLE `webdav_share_entry` (
  `job_id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `relative_path` varchar(2048) NOT NULL,
  `file_size` bigint DEFAULT NULL,
  `result_json` text,
  `filename_json` text NOT NULL,
  PRIMARY KEY (`job_id`,`song_id`)
);

CREATE TABLE `webdav_share_asset` (
  `job_id` varchar(36) NOT NULL,
  `hash` varchar(64) NOT NULL,
  PRIMARY KEY (`job_id`,`hash`)
);

CREATE TABLE `webdav_share_receipt` (
  `source_id` varchar(36) NOT NULL,
  `binding` varchar(64) NOT NULL,
  `hash` varchar(64) NOT NULL,
  `processed` int NOT NULL DEFAULT '0',
  `imported` int NOT NULL DEFAULT '0',
  `skipped` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`source_id`,`binding`,`hash`)
);

CREATE TABLE `media_metadata_file` (
  `song_id` varchar(36) NOT NULL,
  `revision` bigint NOT NULL DEFAULT '1',
  `synced_revision` bigint NOT NULL DEFAULT '0',
  `source_location` varchar(2048) DEFAULT NULL,
  `result_json` longtext,
  `error` varchar(200) DEFAULT NULL,
  `updated_at` varchar(40) DEFAULT NULL,
  PRIMARY KEY (`song_id`),
  KEY `idx_metadata_file_pending` (`synced_revision`,`revision`)
);

CREATE TABLE `artist_image_fetch` (
  `artist_id` varchar(36) NOT NULL,
  `image_kind` varchar(16) NOT NULL,
  `state` varchar(20) NOT NULL DEFAULT 'PENDING',
  `attempts` int NOT NULL DEFAULT '0',
  `fetch_after` varchar(40) NOT NULL DEFAULT '',
  `generation` bigint NOT NULL DEFAULT '0',
  `candidates_json` text,
  PRIMARY KEY (`artist_id`,`image_kind`)
);

CREATE TABLE `artist_artwork_settings` (
  `id` int NOT NULL,
  `fanart_key_encrypted` text,
  PRIMARY KEY (`id`)
);
INSERT INTO artist_artwork_settings(id) VALUES(1);

CREATE TABLE `media_audio_role` (
  `song_id` varchar(36) NOT NULL,
  `fingerprint` varchar(128) NOT NULL,
  `original_id` varchar(16) DEFAULT NULL,
  `backing_id` varchar(16) DEFAULT NULL,
  `origin` varchar(16) NOT NULL,
  `revision` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`song_id`)
);

CREATE TABLE `media_audio_job` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `kind` varchar(16) NOT NULL,
  `state` varchar(16) NOT NULL,
  `fingerprint` varchar(128) NOT NULL,
  `node_origin` varchar(2048) NOT NULL,
  `result_json` text NOT NULL,
  `error` varchar(200) NOT NULL,
  `created_at` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  `backend` varchar(16) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `media_audio_job_pending` (`state`,`created_at`),
  KEY `media_audio_job_song` (`song_id`,`kind`,`fingerprint`)
);

CREATE TABLE `media_audio_import` (
  `song_id` varchar(36) NOT NULL,
  `claim_json` text NOT NULL,
  PRIMARY KEY (`song_id`)
);

CREATE TABLE `audio_batch` (
  `id` varchar(36) NOT NULL,
  `state` varchar(16) NOT NULL,
  `backend` varchar(16) NOT NULL,
  `created_at` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  `selection_total` bigint NOT NULL DEFAULT '0',
  `prepared_count` bigint NOT NULL DEFAULT '0',
  `preparation_error` varchar(200) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`)
);

CREATE TABLE `audio_batch_item` (
  `batch_id` varchar(36) NOT NULL,
  `song_id` varchar(36) NOT NULL,
  `fingerprint` varchar(128) NOT NULL,
  `title` varchar(2048) NOT NULL,
  `category` varchar(32) NOT NULL,
  `state` varchar(16) NOT NULL,
  `job_id` varchar(36) DEFAULT NULL,
  `error` varchar(200) NOT NULL,
  `updated_at` bigint NOT NULL,
  PRIMARY KEY (`batch_id`,`song_id`),
  KEY `audio_batch_item_pending` (`state`,`updated_at`)
);

CREATE TABLE `tv_home_settings` (
  `id` int NOT NULL,
  `headline` varchar(80) NOT NULL,
  `subtitle` varchar(160) NOT NULL,
  `auto_rotate` int NOT NULL DEFAULT '1',
  `rotation_seconds` int NOT NULL DEFAULT '8',
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
);
INSERT INTO tv_home_settings(id,headline,subtitle) VALUES(1,'把生活，唱成热爱。','你的私人音乐现场');

CREATE TABLE `tv_home_banner` (
  `id` varchar(64) NOT NULL,
  `label` varchar(80) NOT NULL,
  `enabled` int NOT NULL DEFAULT '1',
  `sort_order` int NOT NULL,
  `version` varchar(64) NOT NULL,
  `image_bytes` mediumblob NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `song_cache_settings` (
  `id` int NOT NULL,
  `max_bytes` bigint NOT NULL DEFAULT '0',
  `policy` varchar(16) NOT NULL DEFAULT 'HYBRID',
  `ttl_hours` int NOT NULL DEFAULT '168',
  `revision` bigint NOT NULL DEFAULT '0',
  `high_watermark_percent` int NOT NULL DEFAULT '90',
  `low_watermark_percent` int NOT NULL DEFAULT '75',
  PRIMARY KEY (`id`)
);
INSERT INTO song_cache_settings(id) VALUES(1);

CREATE TABLE `song_cache_entry` (
  `id` varchar(36) NOT NULL,
  `cache_key` varchar(64) NOT NULL,
  `song_id` varchar(64) NOT NULL,
  `execution_json` longtext NOT NULL,
  `target_json` longtext NOT NULL,
  `artifacts_json` longtext NOT NULL,
  `media_type` varchar(16) NOT NULL,
  `bytes` bigint NOT NULL,
  `created_at` bigint NOT NULL,
  `last_access_at` bigint NOT NULL,
  `hits` bigint NOT NULL DEFAULT '0',
  `state` varchar(16) NOT NULL DEFAULT 'ACTIVE',
  `delete_token` varchar(36) DEFAULT NULL,
  `delete_retry_at` bigint NOT NULL DEFAULT '0',
  `delete_error` varchar(200) NOT NULL DEFAULT '',
  `residue_bytes` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `cache_key` (`cache_key`),
  KEY `song_cache_song_state` (`song_id`,`state`),
  KEY `song_cache_page` (`last_access_at` DESC,`id`),
  KEY `song_cache_delete_retry` (`state`,`delete_retry_at`)
);

CREATE TABLE `song_cache_lease` (
  `session_id` varchar(36) NOT NULL,
  `cache_id` varchar(36) NOT NULL,
  `renewed_at` bigint NOT NULL,
  PRIMARY KEY (`session_id`),
  KEY `song_cache_lease_active` (`cache_id`,`renewed_at`),
  CONSTRAINT `song_cache_lease_ibfk_1` FOREIGN KEY (`cache_id`) REFERENCES `song_cache_entry` (`id`) ON DELETE CASCADE
);

CREATE TABLE `compute_pairing_identity` (
  `id` int NOT NULL,
  `public_key` text NOT NULL,
  `private_key_encrypted` text NOT NULL,
  PRIMARY KEY (`id`)
);

CREATE TABLE `playback_history` (
  `id` varchar(36) NOT NULL,
  `song_id` varchar(64) NOT NULL,
  `title` varchar(500) NOT NULL,
  `artists_json` longtext NOT NULL,
  `version_label` varchar(200) NOT NULL DEFAULT '',
  `source_name` varchar(200) NOT NULL DEFAULT '',
  `client_type` varchar(16) NOT NULL,
  `device_name` varchar(200) NOT NULL DEFAULT '',
  `requested_by` varchar(200) NOT NULL DEFAULT '',
  `session_id` varchar(64) DEFAULT NULL,
  `attempt_id` varchar(64) DEFAULT NULL,
  `started_at` bigint NOT NULL,
  `ended_at` bigint DEFAULT NULL,
  `played_ms` bigint NOT NULL DEFAULT '0',
  `duration_ms` bigint DEFAULT NULL,
  `status` varchar(16) NOT NULL,
  `effective_at` bigint DEFAULT NULL,
  `heat_applied` int NOT NULL DEFAULT '0',
  `activity_prepared` int NOT NULL DEFAULT '0',
  `last_activity_at` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `client_type` (`client_type`,`attempt_id`),
  KEY `playback_history_recent` (`started_at`,`id`),
  KEY `playback_history_song_recent` (`song_id`,`started_at`,`id`),
  KEY `playback_history_effective` (`effective_at`,`song_id`),
  KEY `playback_history_song_effective` (`song_id`,`effective_at`),
  KEY `playback_history_aggregation` (`heat_applied`,`activity_prepared`,`effective_at`,`started_at`,`id`),
  KEY `playback_history_preparation` (`activity_prepared`,`started_at`,`id`),
  CONSTRAINT `playback_history_chk_1` CHECK ((`client_type` in (_utf8mb4'TV',_utf8mb4'MOBILE',_utf8mb4'ADMIN'))),
  CONSTRAINT `playback_history_chk_2` CHECK ((`played_ms` >= 0)),
  CONSTRAINT `playback_history_chk_3` CHECK (((`duration_ms` is null) or (`duration_ms` > 0))),
  CONSTRAINT `playback_history_chk_4` CHECK ((`status` in (_utf8mb4'PLAYING',_utf8mb4'COMPLETED',_utf8mb4'SKIPPED',_utf8mb4'STOPPED',_utf8mb4'ERROR',_utf8mb4'INTERRUPTED'))),
  CONSTRAINT `playback_history_chk_5` CHECK (((`ended_at` is null) or (`ended_at` >= `started_at`))),
  CONSTRAINT `playback_history_chk_6` CHECK ((`heat_applied` in (0,1))),
  CONSTRAINT `playback_history_chk_7` CHECK ((`activity_prepared` in (0,1)))
);

CREATE TABLE `missing_song_feedback` (
  `id` varchar(36) NOT NULL,
  `title` varchar(200) NOT NULL,
  `artist` varchar(200) NOT NULL,
  `version_label` varchar(120) NOT NULL,
  `description` text NOT NULL,
  `submitted_by` varchar(80) NOT NULL,
  `source` varchar(20) NOT NULL,
  `status` varchar(20) NOT NULL,
  `handling_note` text NOT NULL,
  `resolved_song_id` varchar(64) DEFAULT NULL,
  `resolved_song_title` text,
  `resolved_song_artists_json` text NOT NULL,
  `resolved_at` varchar(40) DEFAULT NULL,
  `search_text` text NOT NULL,
  `creation_hash` varchar(64) NOT NULL,
  `revision` bigint NOT NULL,
  `created_at` varchar(40) NOT NULL,
  `updated_at` varchar(40) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `missing_song_feedback_created` (`created_at` DESC,`id`),
  KEY `missing_song_feedback_status_created` (`status`,`created_at` DESC,`id`)
);

CREATE TABLE `missing_song_feedback_lock` (
  `id` int NOT NULL,
  `revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
);
INSERT INTO missing_song_feedback_lock(id) VALUES(1);

CREATE TABLE `song_cache_cleanup_job` (
  `id` varchar(36) NOT NULL,
  `mode` varchar(16) NOT NULL,
  `state` varchar(16) NOT NULL,
  `created_at` bigint NOT NULL,
  `updated_at` bigint NOT NULL,
  `result_json` longtext NOT NULL,
  PRIMARY KEY (`id`),
  KEY `song_cache_job_recent` (`created_at`,`id`)
);

CREATE TABLE `song_playback_profile` (
  `song_id` varchar(64) NOT NULL,
  `last_played_at` bigint DEFAULT NULL,
  `heat` double NOT NULL DEFAULT '0',
  `heat_updated_at` bigint DEFAULT NULL,
  `pinned` int NOT NULL DEFAULT '0',
  `pin_revision` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`song_id`),
  CONSTRAINT `song_playback_profile_chk_1` CHECK (((`heat` >= 0) and (`heat` <= 1))),
  CONSTRAINT `song_playback_profile_chk_2` CHECK ((`pinned` in (0,1)))
);

CREATE TABLE `playback_attempt` (
  `id` varchar(36) NOT NULL,
  `client_type` varchar(16) NOT NULL,
  `owner_session_id` varchar(64) NOT NULL,
  `token_hash` varchar(64) DEFAULT NULL,
  `song_id` varchar(64) NOT NULL,
  `snapshot_json` longtext NOT NULL,
  `event_seq` bigint NOT NULL DEFAULT '-1',
  `actual_played_ms` bigint NOT NULL DEFAULT '0',
  `elapsed_ms` bigint NOT NULL DEFAULT '0',
  `state` varchar(16) NOT NULL,
  `started_at` bigint NOT NULL,
  `client_started_at` bigint DEFAULT NULL,
  `client_last_activity_at` bigint DEFAULT NULL,
  `started_playing_at` bigint DEFAULT NULL,
  `last_activity_at` bigint DEFAULT NULL,
  `last_event_at` bigint NOT NULL,
  `lease_until` bigint NOT NULL,
  `history_id` varchar(36) DEFAULT NULL,
  `effective_at` bigint DEFAULT NULL,
  `duration_ms` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `playback_attempt_song_lease` (`song_id`,`state`,`lease_until`),
  KEY `playback_attempt_expiry` (`state`,`lease_until`,`id`),
  KEY `playback_attempt_owner` (`client_type`,`owner_session_id`,`state`),
  CONSTRAINT `playback_attempt_chk_1` CHECK ((`client_type` in (_utf8mb4'TV',_utf8mb4'MOBILE',_utf8mb4'ADMIN'))),
  CONSTRAINT `playback_attempt_chk_2` CHECK ((`actual_played_ms` >= 0)),
  CONSTRAINT `playback_attempt_chk_3` CHECK ((`elapsed_ms` >= 0)),
  CONSTRAINT `playback_attempt_chk_4` CHECK ((`state` in (_utf8mb4'PREPARING',_utf8mb4'BUFFERING',_utf8mb4'PLAYING',_utf8mb4'PAUSED',_utf8mb4'COMPLETED',_utf8mb4'STOPPED',_utf8mb4'SKIPPED',_utf8mb4'ERROR',_utf8mb4'INTERRUPTED')))
);

CREATE TABLE `playback_activity_state` (
  `id` int NOT NULL,
  `ready` int NOT NULL DEFAULT '1',
  `phase` varchar(16) NOT NULL DEFAULT 'READY',
  `cursor_started_at` bigint NOT NULL DEFAULT '0',
  `cursor_id` varchar(36) NOT NULL DEFAULT '',
  PRIMARY KEY (`id`),
  CONSTRAINT `playback_activity_state_chk_1` CHECK ((`id` = 1)),
  CONSTRAINT `playback_activity_state_chk_2` CHECK ((`ready` in (0,1)))
);
INSERT INTO playback_activity_state(id) VALUES(1);

-- migrate:down
-- Drop children before their referenced parents.
DROP TABLE `playback_activity_state`;
DROP TABLE `playback_attempt`;
DROP TABLE `song_playback_profile`;
DROP TABLE `song_cache_cleanup_job`;
DROP TABLE `missing_song_feedback_lock`;
DROP TABLE `missing_song_feedback`;
DROP TABLE `playback_history`;
DROP TABLE `compute_pairing_identity`;
DROP TABLE `song_cache_lease`;
DROP TABLE `song_cache_entry`;
DROP TABLE `song_cache_settings`;
DROP TABLE `tv_home_banner`;
DROP TABLE `tv_home_settings`;
DROP TABLE `audio_batch_item`;
DROP TABLE `audio_batch`;
DROP TABLE `media_audio_import`;
DROP TABLE `media_audio_job`;
DROP TABLE `media_audio_role`;
DROP TABLE `artist_artwork_settings`;
DROP TABLE `artist_image_fetch`;
DROP TABLE `media_metadata_file`;
DROP TABLE `webdav_share_receipt`;
DROP TABLE `webdav_share_asset`;
DROP TABLE `webdav_share_entry`;
DROP TABLE `webdav_share_job`;
DROP TABLE `webdav_share_lock`;
DROP TABLE `webdav_access_budget`;
DROP TABLE `webdav_metadata_task`;
DROP TABLE `webdav_resource`;
DROP TABLE `webdav_metadata_source`;
DROP TABLE `media_transcode`;
DROP TABLE `playlist_lock`;
DROP TABLE `playlist`;
DROP TABLE `song_artist_override`;
DROP TABLE `artist_image_policy`;
DROP TABLE `artist_credit_binding`;
DROP TABLE `artist_image`;
DROP TABLE `artist`;
DROP TABLE `artist_registry_lock`;
DROP TABLE `classification_tag_cover`;
DROP TABLE `tv_record`;
DROP TABLE `compute_node_settings`;
DROP TABLE `ai_feature`;
DROP TABLE `ai_service`;
DROP TABLE `ai_registry_lock`;
DROP TABLE `classification_receipt`;
DROP TABLE `classification_history`;
DROP TABLE `classification_item`;
DROP TABLE `classification_task`;
DROP TABLE `classification_assignment`;
DROP TABLE `classification_song`;
DROP TABLE `classification_tag`;
DROP TABLE `classification_settings`;
DROP TABLE `metadata_review_receipt`;
DROP TABLE `metadata_cover_asset`;
DROP TABLE `media_scan_song`;
DROP TABLE `media_scan_directory`;
DROP TABLE `media_scan_task`;
DROP TABLE `song_metadata`;
DROP TABLE `metadata_candidate`;
DROP TABLE `metadata_item`;
DROP TABLE `metadata_batch`;
DROP TABLE `metadata_settings`;
DROP TABLE `media_song`;
DROP TABLE `filename_parser_config`;
DROP TABLE `media_source`;
DROP TABLE `auth_event`;
DROP TABLE `auth_throttle`;
DROP TABLE `auth_recovery`;
DROP TABLE `auth_token`;
DROP TABLE `auth_account`;
