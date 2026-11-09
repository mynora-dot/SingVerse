package com.mynora.singverse.infrastructure.database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Executes the real first-deployment SQLite DDL in isolated JDBC fixtures. */
public final class InitialSchemaFixture {
    public static final String VERSION = "202611070001";
    public static final String[] VERSIONS = {VERSION, "202611080001", "202611090001", "202611090002", "202611090003"};
    private InitialSchemaFixture() {}

    public static void sqlite(Connection connection) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("database/migrations"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Database migrations directory missing");
        Path migration = root.resolve("database/migrations/sqlite/" + VERSION + "_initial_schema.sql");
        String sql = Files.readString(migration);
        String[] sections = sql.split("-- migrate:down", -1);
        if (sections.length != 2 || !sections[0].contains("-- migrate:up"))
            throw new IllegalStateException("Initial schema must have one up and one down section");
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ByteArrayResource(sections[0].getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
}
