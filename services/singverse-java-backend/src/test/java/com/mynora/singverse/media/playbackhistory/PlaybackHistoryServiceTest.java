package com.mynora.singverse.media.playbackhistory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Exercises production XML filtering and aggregation against a migrated, isolated SQLite database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sqlite")
@DirtiesContext
class PlaybackHistoryServiceTest {
    @TempDir static Path root;
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final long DAY = Duration.ofDays(1).toMillis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("singverse.transcoding.cache-directory", () -> root.resolve("cache").toString());
        properties.add("singverse.transcoding.directory", () -> root.resolve("versions").toString());
        properties.add("singverse.audio.directory", () -> root.resolve("audio").toString());
        properties.add("singverse.metadata.cache-path", () -> root.resolve("covers").toString());
        properties.add("singverse.webdav.metadata-path", () -> root.resolve("webdav").toString());
        properties.add("singverse.sqlite.path", () -> root.resolve("playback-history.db").toString());
        properties.add("singverse.auth.key-file", () -> root.resolve("auth.key").toString());
        properties.add("singverse.classification.worker-enabled", () -> false);
    }

    @Autowired PlaybackHistoryMapper mapper;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate fixture;
    PlaybackHistoryService service;

    @BeforeEach
    void prepare() {
        fixture.update("DELETE FROM playback_history");
        service = new PlaybackHistoryService(mapper, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    void record(String id, String song, String title, String artist, long age,
            long played, Long duration, String status) throws Exception {
        long start = NOW.toEpochMilli() - age;
        Long end = status.equals("PLAYING") ? null : start + played;
        fixture.update("INSERT INTO playback_history(id,song_id,title,artists_json,version_label,source_name,"
                        + "client_type,device_name,requested_by,started_at,ended_at,played_ms,duration_ms,status)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, song, title, json.writeValueAsString(List.of(artist)), "MV · 国语", "家庭曲库",
                "TV", "客厅电视", "小林", start, end, played, duration, status);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void emptyDatabaseReturnsRealEmptyPagesAndZeroSummary() {
        assertThat(service.history("", "30d", "", 0, 20))
                .containsEntry("items", List.of()).containsEntry("totalElements", 0L)
                .containsEntry("page", 0).containsEntry("size", 20);
        assertThat(service.popular("", "30d", "", 0, 20))
                .containsEntry("items", List.of()).containsEntry("totalElements", 0L);
        assertThat(service.summary("", "30d", "")).containsExactlyInAnyOrderEntriesOf(
                Map.of("totalPlays", 0L, "effectivePlays", 0L, "playedSongs", 0L, "playedMs", 0L));
    }

    @Test
    void effectivePlayUsesActualListeningAndShortTrackCompletionRatherThanAttemptCount() throws Exception {
        record("01", "main", "七里香", "周杰伦", DAY, 30_000, 269_000L, "SKIPPED");
        record("02", "main", "七里香", "周杰伦", DAY * 2, 29_999, 269_000L, "SKIPPED");
        record("03", "short", "短曲", "测试歌手", DAY, 8_000, 8_000L, "COMPLETED");
        record("04", "short", "短曲", "测试歌手", DAY * 2, 7_999, 8_000L, "COMPLETED");
        record("05", "failed", "失败歌曲", "测试歌手", DAY, 0, null, "ERROR");
        record("06", "unknown", "未知时长", "测试歌手", DAY, 4_000, null, "COMPLETED");
        record("07", "short-error", "短曲失败", "测试歌手", DAY, 8_000, 8_000L, "ERROR");
        record("08", "playing", "进行中", "测试歌手", DAY, 50_000, 100_000L, "PLAYING");
        var summary = service.summary("", "30d", "");
        assertThat(summary).containsEntry("totalPlays", 8L).containsEntry("effectivePlays", 3L)
                .containsEntry("playedSongs", 3L).containsEntry("playedMs", 137_998L);
        var history = items(service.history("", "30d", "", 0, 20));
        assertThat(history.stream().filter(row -> Boolean.TRUE.equals(row.get("effective"))))
                .extracting(row -> row.get("id")).containsExactly("08", "03", "01");
        assertThat(history.getFirst()).containsEntry("endedAt", null).containsEntry("durationMs", 100_000L)
                .containsEntry("startedAt", "2026-11-05T12:00:00Z");
        var popular = service.popular("", "30d", "", 0, 20);
        assertThat(popular).containsEntry("totalElements", 3L);
        assertThat(items(popular)).extracting(row -> row.get("songId")).containsExactly("main", "playing", "short");
        assertThat(items(popular).getFirst()).containsEntry("playCount", 2L)
                .containsEntry("effectivePlayCount", 1L).containsEntry("completedCount", 0L);
    }

    @Test
    void filteringPrecedesPaginationAndRankingUsesLatestMatchingSnapshotWithStableTies() throws Exception {
        record("a1", "song-a", "旧歌名", "Artist A", DAY * 3, 60_000, 269_000L, "STOPPED");
        record("a2", "song-a", "新歌名", "Artist A", DAY, 60_000, 269_000L, "STOPPED");
        record("b1", "song-b", "其他歌曲", "Artist B", DAY * 2, 60_000, 269_000L, "COMPLETED");
        record("b2", "song-b", "其他歌曲", "Artist B", DAY, 60_000, 269_000L, "COMPLETED");
        record("c1", "song-c", "其他歌曲", "Artist C", DAY, 60_000, 269_000L, "COMPLETED");
        assertThat(items(service.popular("", "30d", "", 0, 1)).getFirst())
                .containsEntry("songId", "song-a").containsEntry("title", "新歌名");
        assertThat(items(service.popular("", "30d", "", 1, 1)).getFirst()).containsEntry("songId", "song-b");
        var matches = service.history("  artist b  ", "30d", "", 1, 1);
        assertThat(matches).containsEntry("totalElements", 2L);
        assertThat(items(matches).getFirst()).containsEntry("id", "b1");
        assertThat(service.summary("Artist B", "30d", ""))
                .containsEntry("totalPlays", 2L).containsEntry("effectivePlays", 2L).containsEntry("playedSongs", 1L);
        var onlyOldName = items(service.popular("旧歌名", "30d", "", 0, 20)).getFirst();
        assertThat(onlyOldName).containsEntry("playCount", 1L).containsEntry("title", "旧歌名");
        assertThat(items(service.history("", "30d", "song-a", 0, 20))).hasSize(2);
        assertThat(service.summary("", "30d", "song-a")).containsEntry("totalPlays", 2L);
        assertThat(items(service.history("", "30d", "", 0, 3))).extracting(row -> row.get("id"))
                .containsExactly("c1", "b2", "a2");
        assertThat(items(service.history("", "30d", "", 99, 3))).isEmpty();
    }

    @Test
    void periodsUseUtcRollingBoundsAndExcludeFutureRecordsEvenForAllTime() throws Exception {
        record("recent", "same", "边界曲目", "歌手", 7 * DAY, 30_000, null, "STOPPED");
        record("outside-week", "same", "边界曲目", "歌手", 7 * DAY + 1, 30_000, null, "STOPPED");
        record("month", "same", "边界曲目", "歌手", 30 * DAY, 30_000, null, "STOPPED");
        record("old", "same", "边界曲目", "歌手", 30 * DAY + 1, 30_000, null, "STOPPED");
        record("future", "same", "边界曲目", "歌手", -1, 30_000, null, "STOPPED");
        assertThat(service.summary("", "7d", "")).containsEntry("totalPlays", 1L);
        assertThat(service.summary("", "30d", "")).containsEntry("totalPlays", 3L);
        assertThat(service.summary("", "all", "")).containsEntry("totalPlays", 4L);
        assertThat(items(service.history("", "all", "", 0, 20)))
                .extracting(row -> row.get("id")).doesNotContain("future");
    }

    @Test
    void searchTreatsWildcardsAndSqlLikeInputAsLiteralText() throws Exception {
        record("special", "special", "100%_! 精选", "测试歌手", DAY, 60_000, null, "STOPPED");
        record("other", "other", "100 abc 精选", "测试歌手", DAY, 60_000, null, "STOPPED");
        assertThat(items(service.history("%_!", "all", "", 0, 20)))
                .extracting(row -> row.get("id")).containsExactly("special");
        assertThat(items(service.history("' OR 1=1 --", "all", "", 0, 20))).isEmpty();
    }

    @Test
    void administratorPreviewRemainsInHistoryButNeverAddsPopularity() throws Exception {
        record("preview", "preview-only", "后台试听", "测试歌手", DAY, 180_000, 180_000L, "COMPLETED");
        fixture.update("UPDATE playback_history SET client_type='ADMIN' WHERE id='preview'");
        assertThat(items(service.history("", "all", "", 0, 20)).getFirst())
                .containsEntry("clientType", "ADMIN").containsEntry("effective", false);
        assertThat(items(service.popular("", "all", "", 0, 20))).isEmpty();
        assertThat(service.summary("", "all", "")).containsEntry("totalPlays", 1L)
                .containsEntry("effectivePlays", 0L).containsEntry("playedSongs", 0L)
                .containsEntry("playedMs", 180_000L);
        record("real", "preview-only", "电视播放时的歌名", "测试歌手", DAY * 2, 40_000, 180_000L, "STOPPED");
        var ranked = items(service.popular("", "all", "", 0, 20)).getFirst();
        assertThat(ranked).containsEntry("title", "电视播放时的歌名")
                .containsEntry("playCount", 1L).containsEntry("completedCount", 0L)
                .containsEntry("effectivePlayCount", 1L).containsEntry("lastPlayedAt", "2026-11-04T12:00:00Z");
    }

    @Test
    void catalogDeletionDoesNotEraseHistoryOrItsTitleArtistAndVersionSnapshots() throws Exception {
        String song = "deleted-song";
        fixture.update("INSERT INTO media_song(id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,"
                        + "parser_revision,result_json,needs_review) VALUES(?,?,?,?,?,?,0,'{}',0)",
                song, "removed-source", "deleted.mp4", "history-fixture", NOW.toString(), "fixture");
        record("retained", song, "删除前的歌名", "删除前的歌手", DAY, 45_000, 269_000L, "STOPPED");
        fixture.update("DELETE FROM media_song WHERE id=?", song);
        var item = items(service.history("", "all", song, 0, 20)).getFirst();
        assertThat(item).containsEntry("title", "删除前的歌名").containsEntry("artists", List.of("删除前的歌手"))
                .containsEntry("versionLabel", "MV · 国语").containsEntry("sourceName", "家庭曲库")
                .containsEntry("deviceName", "客厅电视").containsEntry("requestedBy", "小林");
        assertThat(items(service.popular("", "all", song, 0, 20))).hasSize(1);
    }

    @Test
    void invalidFiltersAndPaginationFailBeforeQuerying() {
        for (String period : new String[] {"", "30", "ALL", "1d"}) {
            assertThatThrownBy(() -> service.summary("", period, ""))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
        assertThatThrownBy(() -> service.history("", "30d", "", -1, 20)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.history("", "30d", "", 1_000_001, 20)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.popular("", "30d", "", 0, 101)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.popular("", "30d", "", 0, 0)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.summary("a".repeat(201), "all", "")).hasMessageContaining("400");
        assertThatThrownBy(() -> service.summary("bad\nquery", "all", "")).hasMessageContaining("400");
        assertThatThrownBy(() -> service.summary("", "all", "x".repeat(65))).hasMessageContaining("400");
    }
}
