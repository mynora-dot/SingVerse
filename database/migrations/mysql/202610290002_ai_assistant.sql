-- migrate:up
ALTER TABLE ai_feature ADD COLUMN display_mode VARCHAR(20) NOT NULL DEFAULT 'PET';
ALTER TABLE ai_feature ADD COLUMN pet_id VARCHAR(40) NOT NULL DEFAULT 'music-sprite';
INSERT INTO ai_feature(id,enabled,interval_seconds,display_mode,pet_id) VALUES('assistant',0,3,'PET','music-sprite');
-- migrate:down
DELETE FROM ai_feature WHERE id='assistant';
ALTER TABLE ai_feature DROP COLUMN pet_id;
ALTER TABLE ai_feature DROP COLUMN display_mode;
