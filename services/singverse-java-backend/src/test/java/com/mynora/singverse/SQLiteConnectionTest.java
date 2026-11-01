package com.mynora.singverse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SQLiteConnectionTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add(
                "singverse.sqlite.path", () -> temp.resolve("nested/database/test.db").toString());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TestRestTemplate http;
    @Autowired Environment environment;
    @Autowired org.apache.ibatis.session.SqlSessionFactory sessions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.mynora.singverse.auth.repository.AuthMapper authMapper;
    @Autowired com.mynora.singverse.media.audio.AudioProcessingMapper audio;

    @Test
    void defaultProfileConnectsAndMigratesAccountMediaSourceAndFilenameConfigurationTables() throws Exception {
        assertThat(environment.getDefaultProfiles()).contains("sqlite");
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(Files.exists(temp.resolve("nested/database/test.db"))).isTrue();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("SQLite");
        }
        assertThat(
                        jdbc.queryForList(
                                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT"
                                    + " LIKE 'sqlite_%'",
                                String.class))
                .containsExactlyInAnyOrder(
                        "audio_batch", "audio_batch_item", "media_audio_role", "media_audio_job", "media_audio_import", "schema_migrations", "media_metadata_file", "webdav_share_lock", "webdav_share_job", "webdav_share_entry", "webdav_share_asset", "webdav_share_receipt", "webdav_metadata_source", "webdav_resource", "webdav_metadata_task", "webdav_access_budget",
                        "auth_account",
                        "auth_token",
                        "auth_recovery",
                        "auth_throttle",
                        "auth_event",
                        "media_source",
                        "media_song", "media_scan_task", "media_scan_directory", "media_scan_song",
                        "metadata_cover_asset", "metadata_review_receipt", "metadata_settings", "metadata_batch", "metadata_item", "metadata_candidate", "song_metadata",
                        "playlist", "playlist_lock", "media_transcode", "artist_image_policy", "artist_image_fetch", "artist_artwork_settings", "song_artist_override", "artist_registry_lock", "artist", "artist_image", "artist_credit_binding", "classification_tag_cover", "classification_settings", "classification_tag", "classification_song", "classification_assignment",
                        "classification_task", "classification_item", "classification_history", "classification_receipt",
                        "filename_parser_config", "ai_registry_lock", "ai_service", "ai_feature", "compute_node_settings", "tv_record");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT mode FROM compute_node_settings WHERE id=1", String.class)).isEqualTo("AUTO");
    }

    @Test
    void healthIsAvailableWithoutLeakingDatabaseDetails() {
        var response = http.getForEntity("/api/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("\"status\":\"UP\"")
                .doesNotContain("jdbc:", "components");
    }

    @Test
    void everyMapperOperationIsBoundToXml() {
        var configuration = sessions.getConfiguration();
        assertThat(configuration.getMapperRegistry().getMappers()).hasSizeGreaterThanOrEqualTo(4);
        for (Class<?> mapper : configuration.getMapperRegistry().getMappers()) {
            for (var method : mapper.getDeclaredMethods()) {
                String id = mapper.getName() + "." + method.getName();
                assertThat(configuration.hasStatement(id)).as(id).isTrue();
                assertThat(configuration.getMappedStatement(id).getResource()).endsWith(".xml]");
            }
        }
    }

    @Test
    void mybatisRecoveryOperationsParticipateInSpringTransactionRollback() {
        var before = authMapper.account();
        int codesBefore = authMapper.countRecovery();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            authMapper.insertRecovery("transaction-test-only");
            assertThat(authMapper.countRecovery()).isEqualTo(codesBefore + 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_recovery", Integer.class))
                    .isEqualTo(codesBefore + 1);
            authMapper.recoverAccount("transaction-test-only-hash");
            authMapper.deleteTokens();
            authMapper.deleteRecovery();
            authMapper.deleteThrottle();
            assertThat(authMapper.account().get("password_hash")).isEqualTo("transaction-test-only-hash");
            assertThat(authMapper.countRecovery()).isZero();
            status.setRollbackOnly();
        });
        assertThat(authMapper.account()).isEqualTo(before);
        assertThat(authMapper.countRecovery()).isEqualTo(codesBefore);
    }

    @Test
    void audioBatchSnapshotAndCancellationSurviveMapperReloadAndRespectTerminalStates(){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            audio.insertBatch("audio-test","cpu",1);
            audio.insertItem(java.util.Map.of("batch","audio-test","song","fixture","fingerprint","current","title","测试歌曲","category","UNPROBED","state","PENDING","now",1));
            assertThat(audio.start("audio-test",2)).isEqualTo(1);assertThat(audio.start("audio-test",3)).isZero();
            assertThat(audio.items("audio-test",10,0)).hasSize(1);
            assertThat(audio.pending()).anyMatch(item->"audio-test".equals(item.get("batch_id")));
            audio.updateItem("audio-test","fixture","SUCCEEDED","job","",4);
            assertThat(audio.updateItem("audio-test","fixture","CANCELLED","job","",5)).isZero();
            audio.cancel("audio-test",6);audio.skipCancelled("audio-test",6);
            assertThat(audio.item("audio-test","fixture")).containsEntry("state","SUCCEEDED");
            assertThat(audio.batch("audio-test")).containsEntry("state","CANCELLED");
            assertThat(audio.counts("audio-test")).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(audio.batch("audio-test")).isNull();
    }

}
