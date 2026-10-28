package com.mynora.singverse.media.webdav;

import static org.assertj.core.api.Assertions.*;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.nio.file.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class WebDavMigrationTest {
    @Test void sqliteUpgradePreservesSongsAndIndexesAndAllowsUnknownRemoteAttributes() throws Exception {
        Path migrations = Path.of("../../database/migrations/sqlite");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            apply(db, Files.readString(migrations.resolve("202610280002_media_song.sql")).split("-- migrate:down")[0]);
            try (Statement statement = db.createStatement()) {
                statement.executeUpdate("INSERT INTO media_song VALUES ('song', 'source', '歌曲.mkv', 'hash', 123, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 'default', 1, NULL, '{\"needsReview\":false}')");
            }
            apply(db, Files.readString(migrations.resolve("202610280003_media_song_review.sql")).split("-- migrate:down")[0]);
            String[] migration = Files.readString(migrations.resolve("202610280005_webdav_scan.sql")).split("-- migrate:down");
            apply(db, migration[0]);
            try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT id, file_size, needs_review FROM media_song")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("song");
                assertThat(rows.getLong(2)).isEqualTo(123); assertThat(rows.getInt(3)).isZero(); assertThat(rows.next()).isFalse();
            }
            try (Statement statement = db.createStatement()) {
                statement.executeUpdate("UPDATE media_song SET file_size=NULL, modified_at=NULL");
                try (ResultSet indexes = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('idx_media_song_source', 'idx_media_song_review')")) {
                    assertThat(indexes.next()).isTrue(); assertThat(indexes.getInt(1)).isEqualTo(2);
                }
            }
            apply(db, migration[1]); // Rolling back task support must not invent missing attributes.
            try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT file_size, modified_at FROM media_song")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull(); assertThat(rows.getObject(2)).isNull();
            }
        }
        assertThat(MediaSongRepository.fingerprint("song", null, null)).isEqualTo(MediaSongRepository.fingerprint("song", null, null));
        assertThat(MediaSongRepository.fingerprint("song", null, null)).isNotEqualTo(MediaSongRepository.fingerprint("song", 0L, "2026-01-01T00:00:00Z"));
    }
    private static void apply(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) {
            for (String part : sql.split(";")) if (!part.isBlank()) statement.execute(part);
        }
    }
}
