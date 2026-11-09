-- name: account
SELECT username,password_hash,default_password,mfa_secret,pending_secret,pending_attempts,pending_expires,last_totp_step,revision FROM auth_account WHERE id=1;
-- name: bootstrap
INSERT INTO auth_account(id,username,password_hash,default_password) VALUES(1,'admin',?,1);
-- name: issue
INSERT INTO auth_token(token_hash,kind,csrf,created_at,last_seen,expires_at,attempts,revision) VALUES(?,?,?,?,?,?,0,?);
-- name: expire_tokens
DELETE FROM auth_token WHERE expires_at < ? OR last_seen < ?;
-- name: find_token
SELECT csrf,attempts FROM auth_token WHERE token_hash=? AND kind=? AND expires_at>? AND last_seen>? AND revision=?;
-- name: touch_token
UPDATE auth_token SET last_seen=? WHERE token_hash=?;
-- name: revoke
DELETE FROM auth_token WHERE token_hash=?;
-- name: revoke_all
DELETE FROM auth_token;
-- name: throttle
SELECT failures,blocked_until,updated_at FROM auth_throttle WHERE bucket=?;
-- name: insert_throttle
INSERT INTO auth_throttle(bucket,failures,blocked_until,updated_at) VALUES(?,?,?,?);
-- name: update_throttle
UPDATE auth_throttle SET failures=?,blocked_until=?,updated_at=? WHERE bucket=?;
-- name: clear_limits
DELETE FROM auth_throttle WHERE bucket=? OR bucket=?;
-- name: clear_all_limits
DELETE FROM auth_throttle;
-- name: event
INSERT INTO auth_event(id,event_type,occurred_at) VALUES(?,?,?);
-- name: prune_events
DELETE FROM auth_event WHERE occurred_at < ?;
-- name: accept_step
UPDATE auth_account SET last_totp_step=? WHERE id=1 AND last_totp_step<? AND revision=?;
-- name: consume_recovery
DELETE FROM auth_recovery WHERE code_hash=?;
-- name: attempt
UPDATE auth_token SET attempts=attempts+1 WHERE token_hash=?;
-- name: setup
UPDATE auth_account SET pending_attempts=0,pending_secret=?,pending_expires=? WHERE id=1;
-- name: cancel_setup
UPDATE auth_account SET pending_secret=NULL,pending_expires=0 WHERE id=1;
-- name: setup_attempt
UPDATE auth_account SET pending_attempts=pending_attempts+1 WHERE id=1;
-- name: confirm
UPDATE auth_account SET mfa_secret=pending_secret,pending_secret=NULL,pending_expires=0,last_totp_step=? WHERE id=1;
-- name: clear_recovery
DELETE FROM auth_recovery;
-- name: insert_recovery
INSERT INTO auth_recovery(code_hash) VALUES(?);
-- name: count_recovery
SELECT COUNT(*) FROM auth_recovery;
-- name: disable
UPDATE auth_account SET mfa_secret=NULL,pending_secret=NULL,pending_expires=0,last_totp_step=-1 WHERE id=1;
-- name: password
UPDATE auth_account SET password_hash=?,default_password=0 WHERE id=1;
-- name: username
UPDATE auth_account SET username=? WHERE id=1;
-- name: invalidate
UPDATE auth_account SET revision=revision+1,pending_secret=NULL,pending_expires=0 WHERE id=1;
-- name: reset
UPDATE auth_account SET password_hash=?,default_password=0,mfa_secret=NULL,pending_secret=NULL,pending_expires=0,pending_attempts=0,last_totp_step=-1,revision=revision+1 WHERE id=1;
-- name: secrets
SELECT mfa_secret AS credential_value,'singverse:totp:v1' AS credential_purpose FROM auth_account WHERE mfa_secret IS NOT NULL
UNION ALL SELECT pending_secret,'singverse:totp:v1' FROM auth_account WHERE pending_secret IS NOT NULL
UNION ALL SELECT password_encrypted,'singverse:webdav:v1' FROM media_source WHERE password_encrypted IS NOT NULL
UNION ALL SELECT secret_encrypted,'singverse:classification:v1' FROM classification_settings WHERE secret_encrypted IS NOT NULL
UNION ALL SELECT secret_encrypted,credential_purpose FROM ai_service WHERE secret_encrypted IS NOT NULL
UNION ALL SELECT secret_encrypted,'singverse:compute-node:v1' FROM compute_node_settings WHERE secret_encrypted IS NOT NULL
UNION ALL SELECT private_key_encrypted,'singverse:compute-node:v1' FROM compute_pairing_identity
UNION ALL SELECT secret_encrypted,'singverse:lx-source:v1' FROM lx_script
UNION ALL SELECT fanart_key_encrypted,'singverse:fanart-personal:v1' FROM artist_artwork_settings WHERE fanart_key_encrypted IS NOT NULL;
-- name: task_secrets
SELECT config_json FROM classification_task;
