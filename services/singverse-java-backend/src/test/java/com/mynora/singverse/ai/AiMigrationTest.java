package com.mynora.singverse.ai;

import static org.assertj.core.api.Assertions.*;

import com.mynora.singverse.infrastructure.database.InitialSchemaFixture;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class AiMigrationTest {
    @Test void emptyInitialSchemaSeedsDisabledFeaturesWithoutInventingCredentialsOrServices() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                try (var rows = query.executeQuery("SELECT COUNT(*) FROM ai_service")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
                try (var rows = query.executeQuery("SELECT revision FROM ai_registry_lock WHERE id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isOne(); assertThat(rows.next()).isFalse();
                }
                var features = new ArrayList<String>();
                try (var rows = query.executeQuery("SELECT id,model_service_id,search_service_id,enabled,interval_seconds,"
                        + "revision,display_mode,pet_id FROM ai_feature ORDER BY id")) {
                    while (rows.next()) {
                        features.add(rows.getString("id"));
                        assertThat(rows.getObject("model_service_id")).isNull();
                        assertThat(rows.getObject("search_service_id")).isNull();
                        assertThat(rows.getInt("enabled")).isZero();
                        assertThat(rows.getInt("interval_seconds")).isEqualTo(3);
                        assertThat(rows.getLong("revision")).isOne();
                        assertThat(rows.getString("display_mode")).isEqualTo("PET");
                        assertThat(rows.getString("pet_id")).isEqualTo("music-sprite");
                    }
                }
                assertThat(features).containsExactly("assistant", "classification", "default-search");
            }
        }
    }

    @Test void initialServiceSupportsNoSecretAndEnforcesRequiredConfigurationAndUniqueIds() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                query.executeUpdate("INSERT INTO ai_service(id,kind,name,provider,protocol,endpoint,credential_purpose)"
                        + " VALUES('model','MODEL','测试模型','custom','CHAT_COMPLETIONS','https://example.test/v1/chat/completions','singverse:ai-service:model:v1')");
                try (var row = query.executeQuery("SELECT model,output_mode,secret_encrypted,enabled,timeout_seconds,max_results,revision FROM ai_service WHERE id='model'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("model")).isEmpty();
                    assertThat(row.getString("output_mode")).isEqualTo("JSON_SCHEMA");
                    assertThat(row.getObject("secret_encrypted")).isNull();
                    assertThat(row.getInt("enabled")).isOne();
                    assertThat(row.getInt("timeout_seconds")).isEqualTo(60);
                    assertThat(row.getInt("max_results")).isEqualTo(5);
                    assertThat(row.getLong("revision")).isOne();
                }
                assertThatThrownBy(() -> query.executeUpdate("INSERT INTO ai_feature(id) VALUES('classification')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("UPDATE ai_service SET endpoint=NULL WHERE id='model'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test void classificationSnapshotsHaveTypedProtocolAndCredentialPurposeDefaults() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var config = json.readValue("{\"enabled\":true,\"endpoint\":\"https://example.test/v1/chat/completions\","
                + "\"model\":\"test-model\",\"encryptedKey\":\"test-only\",\"timeoutSeconds\":45,\"intervalSeconds\":7,\"revision\":1}",
                com.mynora.singverse.media.classification.ClassificationDtos.ModelConfig.class);
        assertThat(config.protocol()).isEqualTo("CHAT_COMPLETIONS");
        assertThat(config.credentialPurpose()).isEqualTo("singverse:classification:v1");
    }
}
