-- migrate:up
CREATE TABLE compute_pairing_identity (
 id INTEGER PRIMARY KEY,
 public_key TEXT NOT NULL,
 private_key_encrypted TEXT NOT NULL
);
-- migrate:down
DROP TABLE compute_pairing_identity;
