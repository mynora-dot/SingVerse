package com.mynora.singverse.infrastructure.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseFailureTest {
    @TempDir Path temp;

    @Test void parentFileCannotBeUsedAsDatabaseDirectory() throws Exception {
        Path file = Files.createFile(temp.resolve("file"));
        assertThatThrownBy(() -> new SQLiteDataSourceConfiguration()
            .sqliteDataSource(file.resolve("nested/db.sqlite").toString()))
            .isInstanceOf(java.io.IOException.class);
    }

    @Test void relativeSQLitePathResolvesAgainstWorkingDirectory() throws Exception {
        Path absolute = temp.resolve("relative/nested.sqlite");
        Path relative = Path.of("").toAbsolutePath().relativize(absolute);
        try (var source = (com.zaxxer.hikari.HikariDataSource)
                new SQLiteDataSourceConfiguration().sqliteDataSource(relative.toString());
             var connection = source.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            org.assertj.core.api.Assertions.assertThat(result.next()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.getInt(1)).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(connection.getMetaData().getURL())
                .isEqualTo("jdbc:sqlite:" + absolute.toAbsolutePath().normalize());
        }
        org.assertj.core.api.Assertions.assertThat(Files.exists(absolute)).isTrue();
    }

    @Test void unreachableMysqlFailsRatherThanFallingBackToSqlite() {
        assertThatThrownBy(() -> DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:1/unavailable?connectTimeout=1000&socketTimeout=1000", "invalid", "invalid"))
            .isInstanceOf(java.sql.SQLException.class);
    }
}
