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
                        "schema_migrations",
                        "auth_account",
                        "auth_token",
                        "auth_recovery",
                        "auth_throttle",
                        "auth_event",
                        "media_source",
                        "media_song", "media_scan_task", "media_scan_directory", "media_scan_song",
                        "metadata_cover_asset", "metadata_review_receipt", "metadata_settings", "metadata_batch", "metadata_item", "metadata_candidate", "song_metadata",
                        "classification_settings", "classification_tag", "classification_song", "classification_assignment",
                        "classification_task", "classification_item", "classification_history", "classification_receipt",
                        "filename_parser_config");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
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

}
