package com.mynora.singverse.infrastructure.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("sqlite")
public class SQLiteDataSourceConfiguration {
    @Bean
    DataSource sqliteDataSource(@Value("${singverse.sqlite.path}") String filename) throws IOException {
        Path file = Path.of(filename).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionTestQuery("SELECT 1");
        config.setPoolName("singverse-sqlite");
        return new HikariDataSource(config);
    }
}
