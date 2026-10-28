package com.mynora.singverse.infrastructure.database;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Owns migrations and the process lock for the selected database. */
@Component
public class DatabaseInitializer {
    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock lock;

    @jakarta.annotation.PreDestroy
    public void close() throws Exception {
        if (lock != null) lock.close();
        if (lockChannel != null) lockChannel.close();
    }

    public final Path root;

    public DatabaseInitializer(DataSource source, Environment env) throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(directory.resolve("database/migrations"))) {
            directory = directory.getParent();
            if (directory == null)
                throw new IllegalStateException("Database migrations directory missing");
        }
        root = directory;
        String url;
        String dialect;
        try (var connection = source.getConnection()) {
            String jdbc = connection.getMetaData().getURL();
            dialect = jdbc.startsWith("jdbc:sqlite:") ? "sqlite" : "mysql";
            if (dialect.equals("sqlite")) {
                url = "sqlite:" + jdbc.substring("jdbc:sqlite:".length());
            } else {
                String user =
                        URLEncoder.encode(
                                env.getRequiredProperty("spring.datasource.username"),
                                StandardCharsets.UTF_8);
                String password =
                        URLEncoder.encode(
                                env.getRequiredProperty("spring.datasource.password"),
                                StandardCharsets.UTF_8);
                String address = jdbc.substring("jdbc:mysql://".length()).split("\\?")[0];
                url = "mysql://" + user + ":" + password + "@" + address;
            }
        }
        Path lockPath =
                dialect.equals("sqlite")
                        ? Path.of(url.substring("sqlite:".length()) + ".auth.lock")
                        : root.resolve("data/auth/mysql.auth.lock");
        Files.createDirectories(lockPath.getParent());
        lockChannel =
                java.nio.channels.FileChannel.open(
                        lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            throw new IllegalStateException(
                    "Backend is already running; stop it before recovery or migration");
        }
        String binary = env.getProperty("DBMATE_BIN", root.resolve(".tools/dbmate").toString());
        var process =
                new ProcessBuilder(
                        binary,
                        "--env-file",
                        "/dev/null",
                        "--no-dump-schema",
                        "--migrations-dir",
                        root.resolve("database/migrations/" + dialect).toString(),
                        "migrate");
        process.environment().put("DATABASE_URL", url);
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        var child = process.start();
        if (!child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IllegalStateException("Database migration timed out");
        }
        if (child.exitValue() != 0)
            throw new IllegalStateException(
                    "Database migration failed; check database access and dbmate installation");
    }
}
