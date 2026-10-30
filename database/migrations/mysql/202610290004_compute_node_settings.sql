-- migrate:up
CREATE TABLE compute_node_settings (
  id INTEGER PRIMARY KEY,
  mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  endpoint VARCHAR(512) NOT NULL DEFAULT '',
  secret_encrypted TEXT,
  mappings_json TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO compute_node_settings(id,mode,endpoint,mappings_json) VALUES(1,'AUTO','','[]');
-- migrate:down
DROP TABLE compute_node_settings;
