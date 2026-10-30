-- migrate:up
CREATE TABLE ai_registry_lock (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL);
INSERT INTO ai_registry_lock(id,revision) VALUES(1,1);
CREATE TABLE ai_service (
 id VARCHAR(36) PRIMARY KEY, kind VARCHAR(16) NOT NULL, name VARCHAR(100) NOT NULL,
 provider VARCHAR(40) NOT NULL, protocol VARCHAR(40) NOT NULL, endpoint VARCHAR(2048) NOT NULL,
 model VARCHAR(200) NOT NULL DEFAULT '', output_mode VARCHAR(20) NOT NULL DEFAULT 'JSON_SCHEMA',
 secret_encrypted TEXT, credential_purpose VARCHAR(100) NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
 timeout_seconds INTEGER NOT NULL DEFAULT 60, max_results INTEGER NOT NULL DEFAULT 5, revision BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE ai_feature (
 id VARCHAR(40) PRIMARY KEY, model_service_id VARCHAR(36), search_service_id VARCHAR(36),
 enabled INTEGER NOT NULL DEFAULT 0, interval_seconds INTEGER NOT NULL DEFAULT 3, revision BIGINT NOT NULL DEFAULT 1
);
INSERT INTO ai_service(id,kind,name,provider,protocol,endpoint,model,output_mode,secret_encrypted,credential_purpose,enabled,timeout_seconds,max_results)
 SELECT 'legacy-classification','MODEL','原歌曲分类模型','custom','CHAT_COMPLETIONS',endpoint,model,'JSON_SCHEMA',secret_encrypted,'singverse:classification:v1',1,timeout_seconds,5
 FROM classification_settings WHERE id=1 AND endpoint <> '' AND model <> '';
INSERT INTO ai_feature(id,model_service_id,enabled,interval_seconds)
 SELECT 'classification',CASE WHEN endpoint <> '' AND model <> '' THEN 'legacy-classification' ELSE NULL END,enabled,interval_seconds FROM classification_settings WHERE id=1;
INSERT INTO ai_feature(id,enabled,interval_seconds) VALUES('default-search',0,3);
-- migrate:down
DROP TABLE ai_feature;
DROP TABLE ai_service;
DROP TABLE ai_registry_lock;
