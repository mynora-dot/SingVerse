-- migrate:up
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
-- migrate:down
DROP TABLE auth_event;
DROP TABLE auth_throttle;
DROP TABLE auth_recovery;
DROP TABLE auth_token;
DROP TABLE auth_account;
