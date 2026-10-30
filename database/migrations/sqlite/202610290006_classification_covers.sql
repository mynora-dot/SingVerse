-- migrate:up
CREATE TABLE classification_tag_cover (
 tag_id VARCHAR(36) PRIMARY KEY,
 version VARCHAR(64) NOT NULL,
 content_type VARCHAR(40) NOT NULL,
 image_bytes BLOB NOT NULL,
 FOREIGN KEY (tag_id) REFERENCES classification_tag(id)
);

-- migrate:down
DROP TABLE classification_tag_cover;
