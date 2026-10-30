package com.mynora.singverse.ai;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class AiMigrationTest {
    @Test void existingClassificationIsImportedWithoutRewritingSecretsOrTaskSnapshots()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            apply(db,"CREATE TABLE classification_settings(id INTEGER PRIMARY KEY,enabled INTEGER,endpoint TEXT,model TEXT,secret_encrypted TEXT,timeout_seconds INTEGER,interval_seconds INTEGER); INSERT INTO classification_settings VALUES(1,1,'https://example.test/v1/chat/completions','legacy-model','encrypted-existing-secret',45,7);");
            apply(db,Files.readString(Path.of("../../database/migrations/sqlite/202610290001_ai_settings.sql")).split("-- migrate:down")[0]);
            try(var q=db.createStatement();var row=q.executeQuery("SELECT s.endpoint,s.model,s.secret_encrypted,s.credential_purpose,s.timeout_seconds,f.enabled,f.interval_seconds FROM ai_service s JOIN ai_feature f ON f.model_service_id=s.id WHERE f.id='classification'")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(2)).isEqualTo("legacy-model");assertThat(row.getString(3)).isEqualTo("encrypted-existing-secret");assertThat(row.getString(4)).isEqualTo("singverse:classification:v1");assertThat(row.getInt(5)).isEqualTo(45);assertThat(row.getInt(6)).isEqualTo(1);assertThat(row.getInt(7)).isEqualTo(7);
            }
        }
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var old=json.readValue("{\"enabled\":true,\"endpoint\":\"https://example.test/v1/chat/completions\",\"model\":\"legacy-model\",\"encryptedKey\":\"secret\",\"timeoutSeconds\":45,\"intervalSeconds\":7,\"revision\":1}",com.mynora.singverse.media.classification.ClassificationDtos.ModelConfig.class);
        assertThat(old.protocol()).isEqualTo("CHAT_COMPLETIONS");assertThat(old.credentialPurpose()).isEqualTo("singverse:classification:v1");
    }
    @Test void emptyLegacyConfigurationCreatesNoInvalidService()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            apply(db,"CREATE TABLE classification_settings(id INTEGER PRIMARY KEY,enabled INTEGER,endpoint TEXT,model TEXT,secret_encrypted TEXT,timeout_seconds INTEGER,interval_seconds INTEGER); INSERT INTO classification_settings VALUES(1,0,'','',NULL,60,3);");
            apply(db,Files.readString(Path.of("../../database/migrations/sqlite/202610290001_ai_settings.sql")).split("-- migrate:down")[0]);
            try(var q=db.createStatement();var row=q.executeQuery("SELECT COUNT(*) FROM ai_service")){assertThat(row.next()).isTrue();assertThat(row.getInt(1)).isZero();}
        }
    }
    private static void apply(Connection db,String sql)throws Exception {try(var s=db.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);}}
}
