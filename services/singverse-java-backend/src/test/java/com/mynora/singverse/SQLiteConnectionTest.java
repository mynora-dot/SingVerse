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
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SQLiteConnectionTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
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
    @Autowired com.mynora.singverse.media.audio.AudioRoleMapper audioRoles;

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
                        "lx_download_settings", "lx_script", "lx_catalog_track", "lx_download_batch", "lx_download_item", "lx_download_asset", "background_task", "background_task_item", "background_task_relation", "background_scan_scope", "audio_batch", "audio_batch_item", "media_audio_role", "media_audio_job", "media_audio_selection", "media_audio_import", "schema_migrations", "media_metadata_file", "webdav_share_lock", "webdav_share_job", "webdav_share_entry", "webdav_share_asset", "webdav_share_receipt", "webdav_metadata_source", "webdav_resource", "webdav_metadata_task", "webdav_access_budget",
                        "playback_history", "playback_attempt", "song_playback_profile", "playback_activity_state",
                        "song_cache_cleanup_job", "missing_song_feedback", "missing_song_feedback_lock",
                        "auth_account",
                        "auth_token",
                        "auth_recovery",
                        "auth_throttle",
                        "auth_event",
                        "media_source",
                        "media_song", "media_scan_task", "media_scan_directory", "media_scan_song",
                        "metadata_cover_asset", "metadata_review_receipt", "metadata_settings", "metadata_batch", "metadata_item", "metadata_candidate", "song_metadata",
                        "playlist", "playlist_lock", "media_transcode", "song_cache_settings", "song_cache_entry", "song_cache_lease", "artist_image_policy", "artist_image_fetch", "artist_artwork_settings", "song_artist_override", "artist_registry_lock", "artist", "artist_image", "artist_credit_binding", "classification_tag_cover", "classification_settings", "classification_tag", "classification_song", "classification_assignment",
                        "classification_task", "classification_item", "classification_history", "classification_receipt",
                        "filename_parser_config", "ai_registry_lock", "ai_service", "ai_feature", "compute_node_settings", "compute_pairing_identity", "tv_record", "tv_home_settings", "tv_home_banner");
        assertThat(jdbc.queryForObject("SELECT rotation_seconds FROM tv_home_settings WHERE id=1", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tv_home_banner", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM missing_song_feedback", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT mode FROM compute_node_settings WHERE id=1", String.class)).isEqualTo("AUTO");
        assertThat(jdbc.queryForList("SELECT version FROM schema_migrations", String.class))
                .containsExactlyInAnyOrder(com.mynora.singverse.infrastructure.database.InitialSchemaFixture.VERSIONS);
        assertThat(jdbc.queryForObject("SELECT ready FROM playback_activity_state WHERE id=1", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT phase FROM playback_activity_state WHERE id=1", String.class)).isEqualTo("READY");
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
            audio.insertBatch("audio-test","cpu",1,1);
            audio.insertItems(java.util.List.of(java.util.Map.of("batch","audio-test","song","fixture","fingerprint","current","title","测试歌曲","category","UNPROBED","state","PENDING","error","","now",1)));
            audio.prepared("audio-test",1,1);audio.ready("audio-test",1);
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

    @Test
    void audioPreparationProgressRecoveryAndBulkHistoryAreBoundedAndFingerprintSpecific(){
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            audio.insertBatch("audio-preparing","",1,2);
            assertThat(audio.ready("audio-preparing",2)).isZero();
            assertThat(audio.lockPreparing("audio-preparing",2)).isEqualTo(1);
            audio.prepared("audio-preparing",1,2);
            assertThat(audio.batch("audio-preparing")).containsEntry("prepared_count",1);
            audio.cancel("audio-preparing",3);
            assertThat(audio.lockPreparing("audio-preparing",4)).isZero();
            assertThat(audio.ready("audio-preparing",4)).isZero();
            audio.insertBatch("audio-interrupted","",1,2);
            assertThat(audio.recoverPreparing(5)).isEqualTo(1);
            assertThat(audio.batch("audio-interrupted")).containsEntry("state","FAILED");
            assertThat(audio.batch("audio-preparing")).containsEntry("state","CANCELLED");

            for(int i=0;i<25;i++)for(String song:java.util.List.of("bulk-a","bulk-b"))
                audioRoles.insertJob(java.util.Map.of("id",song+"-"+String.format("%02d",i),"song_id",song,
                        "kind","PROBE","state","SUCCEEDED","fingerprint","current","node_origin","test",
                        "created_at",i,"updated_at",i,"backend",""));
            audioRoles.insertJob(java.util.Map.of("id","bulk-stale","song_id","bulk-a","kind","PROBE",
                    "state","SUCCEEDED","fingerprint","stale","node_origin","test","created_at",99,"updated_at",99,"backend",""));
            var history=audioRoles.jobsForSongs(java.util.List.of(java.util.Map.of("id","bulk-a","fingerprint","current"),java.util.Map.of("id","bulk-b","fingerprint","current")));
            assertThat(history).hasSize(40).allMatch(row->"current".equals(row.get("fingerprint")));
            assertThat(history.stream().filter(row->"bulk-a".equals(row.get("song_id"))).toList())
                    .hasSize(20).first().extracting(row->row.get("id")).isEqualTo("bulk-a-24");
            status.setRollbackOnly();
        });
    }

}
