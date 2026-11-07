package com.mynora.singverse.infrastructure.database;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseBaselineTest {
    @TempDir Path temp;

    @Test void fromZeroCacheAndActivityDefaultsAreReadyWithoutLegacyBackfill() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                try (var row = query.executeQuery("SELECT max_bytes,policy,ttl_hours,revision,high_watermark_percent,low_watermark_percent"
                        + " FROM song_cache_settings WHERE id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong("max_bytes")).isZero();
                    assertThat(row.getString("policy")).isEqualTo("HYBRID");
                    assertThat(row.getInt("ttl_hours")).isEqualTo(168);
                    assertThat(row.getLong("revision")).isZero();
                    assertThat(row.getInt("high_watermark_percent")).isEqualTo(90);
                    assertThat(row.getInt("low_watermark_percent")).isEqualTo(75);
                    assertThat(row.next()).isFalse();
                }
                try (var row = query.executeQuery("SELECT ready,phase,cursor_started_at,cursor_id FROM playback_activity_state WHERE id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt("ready")).isOne();
                    assertThat(row.getString("phase")).isEqualTo("READY");
                    assertThat(row.getLong("cursor_started_at")).isZero();
                    assertThat(row.getString("cursor_id")).isEmpty();
                    assertThat(row.next()).isFalse();
                }
                query.executeUpdate("INSERT INTO song_cache_entry(id,cache_key,song_id,execution_json,target_json,artifacts_json,media_type,bytes,created_at,last_access_at)"
                        + " VALUES('cache','key','song','{}','{}','{}','VIDEO',0,1,1)");
                try (var row = query.executeQuery("SELECT state,residue_bytes,delete_token,delete_retry_at,delete_error,hits FROM song_cache_entry WHERE id='cache'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("state")).isEqualTo("ACTIVE");
                    assertThat(row.getLong("residue_bytes")).isZero();
                    assertThat(row.getObject("delete_token")).isNull();
                    assertThat(row.getLong("delete_retry_at")).isZero();
                    assertThat(row.getString("delete_error")).isEmpty();
                    assertThat(row.getLong("hits")).isZero();
                }
            }
        }
    }

    @Test void fromZeroPlaybackRowsEnforceCountersAndTypesWhileAllowingUnknownClocks() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                query.executeUpdate("INSERT INTO song_playback_profile(song_id) VALUES('song')");
                try (var row = query.executeQuery("SELECT last_played_at,heat,heat_updated_at,pinned,pin_revision FROM song_playback_profile WHERE song_id='song'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getObject("last_played_at")).isNull();
                    assertThat(row.getDouble("heat")).isZero();
                    assertThat(row.getObject("heat_updated_at")).isNull();
                    assertThat(row.getInt("pinned")).isZero();
                    assertThat(row.getLong("pin_revision")).isZero();
                }
                assertThatThrownBy(() -> query.executeUpdate("UPDATE song_playback_profile SET heat=1.01 WHERE song_id='song'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("UPDATE song_playback_profile SET pinned=2 WHERE song_id='song'"))
                        .isInstanceOf(SQLException.class);
                query.executeUpdate("INSERT INTO playback_attempt(id,client_type,owner_session_id,song_id,snapshot_json,state,started_at,last_event_at,lease_until)"
                        + " VALUES('attempt','TV','session','song','{}','PREPARING',1000,1000,121000)");
                try (var row = query.executeQuery("SELECT event_seq,actual_played_ms,elapsed_ms,token_hash,client_started_at,client_last_activity_at,started_playing_at,last_activity_at,duration_ms,effective_at"
                        + " FROM playback_attempt WHERE id='attempt'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong("event_seq")).isEqualTo(-1);
                    assertThat(row.getLong("actual_played_ms")).isZero();
                    assertThat(row.getLong("elapsed_ms")).isZero();
                    for (int column = 4; column <= 10; column++) assertThat(row.getObject(column)).isNull();
                }
                assertThatThrownBy(() -> query.executeUpdate("UPDATE playback_attempt SET actual_played_ms=-1 WHERE id='attempt'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("UPDATE playback_attempt SET elapsed_ms=-1 WHERE id='attempt'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("UPDATE playback_attempt SET client_type='UNKNOWN' WHERE id='attempt'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("UPDATE playback_attempt SET state='UNKNOWN' WHERE id='attempt'"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test void realInitializerSecondStartDoesNotDuplicateSeedsOrResetUserConfiguration() throws Exception {
        try (var source = (HikariDataSource) new SQLiteDataSourceConfiguration()
                .sqliteDataSource(temp.resolve("nested/fresh.db").toString())) {
            var environment = new StandardEnvironment();
            var first = new DatabaseInitializer(source, environment);
            try {
                var jdbc = new JdbcTemplate(source);
                assertThat(jdbc.queryForList("SELECT version FROM schema_migrations", String.class))
                        .containsExactly(InitialSchemaFixture.VERSION);
                jdbc.update("UPDATE song_cache_settings SET max_bytes=67108864,revision=7 WHERE id=1");
                jdbc.update("UPDATE tv_home_settings SET rotation_seconds=12,revision=4 WHERE id=1");
                jdbc.update("INSERT INTO song_playback_profile(song_id,pinned,pin_revision) VALUES('kept-song',1,3)");
            } finally { first.close(); }
            var jdbc = new JdbcTemplate(source);
            var before = seedCounts(jdbc);
            var second = new DatabaseInitializer(source, environment);
            try {
                assertThat(jdbc.queryForList("SELECT version FROM schema_migrations", String.class))
                        .containsExactly(InitialSchemaFixture.VERSION);
                assertThat(seedCounts(jdbc)).isEqualTo(before);
                assertThat(jdbc.queryForObject("SELECT max_bytes FROM song_cache_settings WHERE id=1", Long.class)).isEqualTo(67108864L);
                assertThat(jdbc.queryForObject("SELECT revision FROM song_cache_settings WHERE id=1", Long.class)).isEqualTo(7L);
                assertThat(jdbc.queryForObject("SELECT rotation_seconds FROM tv_home_settings WHERE id=1", Integer.class)).isEqualTo(12);
                assertThat(jdbc.queryForObject("SELECT revision FROM tv_home_settings WHERE id=1", Long.class)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("SELECT pinned FROM song_playback_profile WHERE song_id='kept-song'", Integer.class)).isOne();
                assertThat(jdbc.queryForObject("SELECT pin_revision FROM song_playback_profile WHERE song_id='kept-song'", Long.class)).isEqualTo(3L);
            } finally { second.close(); }
        }
    }

    private static Map<String, Long> seedCounts(JdbcTemplate jdbc) {
        return Map.of(
                "features", jdbc.queryForObject("SELECT COUNT(*) FROM ai_feature", Long.class),
                "tags", jdbc.queryForObject("SELECT COUNT(*) FROM classification_tag", Long.class),
                "cacheSettings", jdbc.queryForObject("SELECT COUNT(*) FROM song_cache_settings", Long.class),
                "activityState", jdbc.queryForObject("SELECT COUNT(*) FROM playback_activity_state", Long.class),
                "tvHome", jdbc.queryForObject("SELECT COUNT(*) FROM tv_home_settings", Long.class),
                "profiles", jdbc.queryForObject("SELECT COUNT(*) FROM song_playback_profile", Long.class));
    }
}
