-- migrate:up
CREATE TABLE filename_parser_config (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  revision BIGINT NOT NULL,
  rules_json MEDIUMTEXT NOT NULL,
  updated_at VARCHAR(30) NOT NULL
);
-- migrate:down
DROP TABLE filename_parser_config;
