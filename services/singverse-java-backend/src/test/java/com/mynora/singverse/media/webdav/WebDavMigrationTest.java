package com.mynora.singverse.media.webdav;

import static org.assertj.core.api.Assertions.*;

import com.mynora.singverse.infrastructure.database.InitialSchemaFixture;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class WebDavMigrationTest {
    @Test void initialSchemaAllowsUnknownRemoteAttributesAndRetainsSongLookupIndexes() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                query.executeUpdate("INSERT INTO media_song(id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,parser_revision,result_json)"
                        + " VALUES('song','source','歌曲.mkv','hash','2026-01-01T00:00:00Z','default',1,'{}')");
                try (var row = query.executeQuery("SELECT id,file_size,modified_at,needs_review FROM media_song WHERE id='song'")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString("id")).isEqualTo("song");
                    assertThat(row.getObject("file_size")).isNull(); assertThat(row.getObject("modified_at")).isNull();
                    assertThat(row.getInt("needs_review")).isOne(); assertThat(row.next()).isFalse();
                }
                query.executeUpdate("UPDATE media_song SET file_size=123,modified_at='2026-01-01T00:00:00Z',needs_review=0 WHERE id='song'");
                query.executeUpdate("UPDATE media_song SET file_size=NULL,modified_at=NULL WHERE id='song'");
                try (var row = query.executeQuery("SELECT file_size,modified_at,needs_review FROM media_song WHERE id='song'")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getObject(1)).isNull();
                    assertThat(row.getObject(2)).isNull(); assertThat(row.getInt(3)).isZero();
                }
                try (var indexes = query.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                        + " AND name IN ('idx_media_song_source','idx_media_song_review','idx_scan_task_source','idx_scan_directory_pending')")) {
                    assertThat(indexes.next()).isTrue(); assertThat(indexes.getInt(1)).isEqualTo(4);
                }
                assertThatThrownBy(() -> query.executeUpdate("INSERT INTO media_song(id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,parser_revision,result_json)"
                        + " VALUES('duplicate','source','other.mkv','hash','2026-01-01T00:00:00Z','default',1,'{}')"))
                        .isInstanceOf(SQLException.class);
            }
        }
        assertThat(MediaSongRepository.fingerprint("song", null, null)).isEqualTo(MediaSongRepository.fingerprint("song", null, null));
        assertThat(MediaSongRepository.fingerprint("song", null, null)).isNotEqualTo(MediaSongRepository.fingerprint("song", 0L, "2026-01-01T00:00:00Z"));
    }

    @Test void initialScanSchemaAllowsInactiveHistoryButOnlyOneActiveTaskPerSource() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            InitialSchemaFixture.sqlite(db);
            try (var query = db.createStatement()) {
                query.executeUpdate("INSERT INTO media_scan_task(id,source_id,active_source,status,phase,created_at)"
                        + " VALUES('active','source','source','QUEUED','LISTING','2026-01-01T00:00:00Z')");
                try (var row = query.executeQuery("SELECT directories,entries,total_songs,cancel_requested,added_songs,reused_songs,missing_songs,removed_songs,started_at,finished_at"
                        + " FROM media_scan_task WHERE id='active'")) {
                    assertThat(row.next()).isTrue();
                    for (int column = 1; column <= 8; column++) assertThat(row.getLong(column)).isZero();
                    assertThat(row.getObject("started_at")).isNull(); assertThat(row.getObject("finished_at")).isNull();
                }
                assertThatThrownBy(() -> query.executeUpdate("INSERT INTO media_scan_task(id,source_id,active_source,status,phase,created_at)"
                        + " VALUES('conflict','source','source','QUEUED','LISTING','2026-01-01T00:00:00Z')"))
                        .isInstanceOf(SQLException.class);
                query.executeUpdate("INSERT INTO media_scan_task(id,source_id,status,phase,created_at)"
                        + " VALUES('done-1','source','SUCCEEDED','DONE','2026-01-01T00:00:00Z'),('done-2','source','SUCCEEDED','DONE','2026-01-01T00:00:00Z')");
                query.executeUpdate("INSERT INTO media_scan_song(task_id,id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,parser_revision,result_json,needs_review)"
                        + " VALUES('active','song','source','歌曲.mkv','hash','2026-01-01T00:00:00Z','default',1,'{}',1)");
                try (var row = query.executeQuery("SELECT file_size,modified_at,etag FROM media_scan_song WHERE id='song'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getObject(1)).isNull(); assertThat(row.getObject(2)).isNull(); assertThat(row.getObject(3)).isNull();
                }
            }
        }
    }
}
