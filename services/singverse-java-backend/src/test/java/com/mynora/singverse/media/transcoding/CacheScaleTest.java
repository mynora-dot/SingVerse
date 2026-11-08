package com.mynora.singverse.media.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Real migrated SQLite/XML and production ranking; bulk rows deliberately have no media files. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sqlite")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheScaleTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
    private static final long NOW = CLOCK.millis(), DAY = CacheEvictionPolicy.DAY;
    private static final String MAPPER = SongCacheMapper.class.getName() + ".";
    private static final String SONG_MAPPER = "com.mynora.singverse.media.repository.MediaSongMapper.find";
    private static final String SESSION = new UUID(2, 1).toString();
    private static final String OWNER = "SingVerse song cache v1\n";
    @TempDir static Path temp;

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("cache-scale.db").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("application-cache").toString());
        registry.add("singverse.transcoding.cache-minimum-free-bytes", () -> "0");
        registry.add("singverse.classification.worker-enabled", () -> "false");
        registry.add("singverse.playback-activity.worker-enabled", () -> "false");
        registry.add("singverse.compute.base-url", () -> "");
    }

    @Autowired DataSource database;
    @Autowired SqlSessionFactory sessions;
    @Autowired SongCacheMapper mapper;
    @Autowired MediaSongRepository songs;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SongCacheService applicationCache;

    @Test void tenAndHundredThousandCacheRowsUseBoundedQueriesAndConstantHotFileChecks() throws Exception {
        var counter = new SqlCounter();
        sessions.getConfiguration().addInterceptor(counter);
        var measurements = new ArrayList<Map<String, Object>>();
        Path directory = Files.createDirectories(temp.resolve("measured-cache"));
        writeSingleMediaFixture(directory);
        try (var files = Files.walk(directory)) { assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(3); }
        var service = new SongCacheService(mock(TranscodeClient.class), mapper, songs, json, transactions,
                directory.toString(), 1L << 50, 0);
        service.clock(CLOCK);
        Map<String, Integer> baselineFileChecks = null;
        try {
            int previous = 0;
            for (int total : new int[] {10_000, 100_000}) {
                long seedStart = System.nanoTime();
                seed(previous, total);
                previous = total;
                long seedNanos = System.nanoTime() - seedStart;
                if (total > 10_000) {
                    service.close();
                    service = new SongCacheService(mock(TranscodeClient.class), mapper, songs, json, transactions,
                            directory.toString(), 1L << 50, 0);
                    service.clock(CLOCK);
                }
                // Cold validation is outside the hot-path measurement and uses only this one fixture.
                assertThat(service.acquire("cache-scale-key-0", SESSION)).isNotNull();
                warmHotPath(service);
                mapper.page(total / 2, 20, NOW - 7 * DAY, NOW);

                long beforeHeap = usedHeap(), beforeAllocation = allocated();
                counter.begin();
                long start = System.nanoTime();
                var candidates = mapper.candidates(NOW - 7 * DAY, NOW);
                long loaded = System.nanoTime(), afterLoadAllocation = allocated();
                var ranked = SongCacheService.rankCandidates(candidates, NOW);
                long sorted = System.nanoTime(), afterSortAllocation = allocated(), afterHeap = usedHeap();
                var candidateSql = counter.end();
                assertThat(candidateSql.statements).isEqualTo(1);
                assertThat(candidateSql.queries).containsExactlyEntriesOf(Map.of(MAPPER + "candidates", 1));
                assertRanking(candidates, ranked, total);

                long[] pageNanos = new long[7];
                long pageAllocated = allocated();
                for (int sample = 0; sample < pageNanos.length; sample++) {
                    counter.begin();
                    long pageStart = System.nanoTime();
                    var page = mapper.page(total / 2, 20, NOW - 7 * DAY, NOW);
                    pageNanos[sample] = System.nanoTime() - pageStart;
                    var pageSql = counter.end();
                    assertThat(pageSql.statements).isEqualTo(1);
                    assertThat(pageSql.queries).containsExactlyEntriesOf(Map.of(MAPPER + "page", 1));
                    assertThat(page).hasSize(20);
                    assertThat(page.getFirst().get("id")).isEqualTo(id(total / 2));
                    assertThat(page.getLast().get("id")).isEqualTo(id(total / 2 + 19));
                }
                pageAllocated = allocationDelta(pageAllocated, allocated());
                if (pageAllocated >= 0) pageAllocated /= pageNanos.length;
                Arrays.sort(pageNanos);

                // Exercise the production management projection, including its bounded 20 missing-song lookups.
                service.list(0, 20);
                counter.begin();
                long listStart = System.nanoTime();
                var listed = service.list(0, 20);
                long listNanos = System.nanoTime() - listStart;
                var listSql = counter.end();
                assertThat(listed.path("items")).hasSize(20);
                assertThat(listed.path("totalElements").asInt()).isEqualTo(total);
                assertThat(listSql.queries.get(MAPPER + "page")).isEqualTo(1);
                assertThat(listSql.queries.get(SONG_MAPPER)).isEqualTo(20);
                assertThat(listSql.statements).isLessThanOrEqualTo(30);

                Map<String, Integer> checks = hotFileChecks(service);
                assertThat(checks.getOrDefault("walk", 0)).isZero();
                assertThat(checks.getOrDefault("list", 0)).isZero();
                assertThat(checks.values().stream().mapToInt(Integer::intValue).sum()).isBetween(1, 128);
                if (baselineFileChecks != null) assertThat(checks).isEqualTo(baselineFileChecks);
                baselineFileChecks = checks;

                var measurement = new LinkedHashMap<String, Object>();
                measurement.put("cacheRows", total);
                measurement.put("candidateRows", candidates.size());
                measurement.put("historyRows", total + 6);
                measurement.put("seedMs", millis(seedNanos));
                measurement.put("candidateStatements", candidateSql.statements);
                measurement.put("candidateLoadMs", millis(loaded - start));
                measurement.put("rankMs", millis(sorted - loaded));
                measurement.put("loadAllocatedBytes", allocationDelta(beforeAllocation, afterLoadAllocation));
                measurement.put("rankAllocatedBytes", allocationDelta(afterLoadAllocation, afterSortAllocation));
                measurement.put("heapDeltaApproxBytes", afterHeap - beforeHeap);
                measurement.put("pageSize", 20);
                measurement.put("pageStatements", 1);
                measurement.put("pageMedianMs", millis(pageNanos[3]));
                measurement.put("pageMaxMs", millis(pageNanos[6]));
                measurement.put("pageAllocatedBytesApprox", pageAllocated);
                measurement.put("serviceListMs", millis(listNanos));
                measurement.put("serviceListStatements", listSql.statements);
                measurement.put("serviceListQueries", listSql.queries);
                measurement.put("hotFilesCalls", checks);
                measurements.add(measurement);
                System.out.println("SQLITE_CACHE_SCALE " + json.writeValueAsString(measurement));
            }
        } finally {
            counter.end();
            service.close();
            writeReport(measurements);
        }
    }

    private void assertRanking(List<Map<String, Object>> candidates, List<Map<String, Object>> ranked, int total) {
        assertThat(candidates).hasSize(total - 1); // One DELETING row is excluded by the real candidate XML.
        assertThat(ranked).hasSameSizeAs(candidates);
        var remaining = new IdentityHashMap<Map<String, Object>, Boolean>();
        candidates.forEach(row -> remaining.put(row, true));
        double priority = -1;
        for (var row : ranked) {
            assertThat(remaining.remove(row)).as("each candidate appears exactly once").isTrue();
            var score = CacheEvictionPolicy.score(((Number) row.get("heat")).doubleValue(), nullable(row, "heat_updated_at"),
                    nullable(row, "last_played_at"), number(row, "plays_7d"), number(row, "created_at"), NOW);
            assertThat(score.priority()).isBetween(priority, 1.0);
            priority = score.priority();
        }
        assertThat(remaining).isEmpty();
        var first = candidates.stream().filter(row -> id(0).equals(row.get("id"))).findFirst().orElseThrow();
        assertThat(number(first, "plays_7d")).isEqualTo(3); // ADMIN, future and >7-day events do not count.
        assertThat(number(first, "pinned")).isEqualTo(1);
        assertThat(number(first, "playing_count")).isEqualTo(1);
        assertThat(number(first, "lease_count")).isEqualTo(2);
        assertThat(candidates).noneMatch(row -> id(total - 1).equals(row.get("id")));
    }

    private void seed(int from, int total) throws Exception {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (from == 0) {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate("UPDATE playback_activity_state SET ready=1,phase='READY' WHERE id=1");
                    }
                } else {
                    try (var restore = connection.prepareStatement("UPDATE song_cache_entry SET state='ACTIVE' WHERE id=?")) {
                        restore.setString(1, id(from - 1)); restore.executeUpdate();
                    }
                }
                try (var entry = connection.prepareStatement("INSERT INTO song_cache_entry "
                        + "(id,cache_key,song_id,execution_json,target_json,artifacts_json,media_type,bytes,created_at,last_access_at,hits,state) "
                        + "VALUES (?,?,?,'{\"ready\":true}',?,?,'VIDEO',?,?,?,0,'ACTIVE')");
                     var profile = connection.prepareStatement("INSERT INTO song_playback_profile "
                             + "(song_id,last_played_at,heat,heat_updated_at,pinned,pin_revision) VALUES (?,?,?,?,?,0)");
                     var history = connection.prepareStatement("INSERT INTO playback_history "
                             + "(id,song_id,title,client_type,started_at,ended_at,played_ms,duration_ms,status,effective_at,heat_applied,activity_prepared) "
                             + "VALUES (?,?,'规模夹具',?,?,?,30000,180000,'COMPLETED',?,1,1)");
                     var attempt = connection.prepareStatement("INSERT INTO playback_attempt "
                             + "(id,client_type,owner_session_id,song_id,snapshot_json,state,started_at,started_playing_at,last_event_at,lease_until) "
                             + "VALUES (?,'TV','cache-scale-owner',?,'{}','PLAYING',?,?,?,?)");
                     var lease = connection.prepareStatement("INSERT INTO song_cache_lease(session_id,cache_id,renewed_at) VALUES (?,?,?)")) {
                    for (int index = from; index < total; index++) {
                        long age = (index % 91L) * DAY, bytes = 1_048_576L + index % 4096;
                        entry.setString(1, id(index)); entry.setString(2, "cache-scale-key-" + index); entry.setString(3, song(index));
                        entry.setString(4, index == 0 ? "{\"mode\":\"TRANSCODE\"}" : "{\"mode\":\"ORIGINAL\",\"sourceBytes\":" + bytes + "}");
                        entry.setString(5, index == 0 ? "{\"master.m3u8\":{\"size\":8},\"stream_v0_0.ts\":{\"size\":1}}" : "{}");
                        entry.setLong(6, bytes); entry.setLong(7, NOW - 100 * DAY); entry.setLong(8, NOW - index); entry.addBatch();
                        profile.setString(1, song(index));
                        if (index % 7 == 0) profile.setNull(2, java.sql.Types.BIGINT); else profile.setLong(2, NOW - age);
                        profile.setDouble(3, (index % 101) / 100.0); profile.setLong(4, NOW - age);
                        profile.setInt(5, index % 97 == 0 ? 1 : 0); profile.addBatch();
                        addHistory(history, "history-" + index, song(index), index % 11 == 0 ? "ADMIN" : "MOBILE", NOW - (index % 14L) * DAY);
                        if (index % 1000 == 0) {
                            attempt.setString(1, "attempt-" + index); attempt.setString(2, song(index));
                            attempt.setLong(3, NOW - 1000); attempt.setLong(4, NOW - 1000);
                            attempt.setLong(5, NOW); attempt.setLong(6, NOW + 120_000); attempt.addBatch();
                        }
                        if (index % 500 == 0) {
                            lease.setString(1, new UUID(1, index).toString()); lease.setString(2, id(index)); lease.setLong(3, NOW); lease.addBatch();
                        }
                        if ((index + 1) % 500 == 0) {
                            entry.executeBatch(); profile.executeBatch(); history.executeBatch(); attempt.executeBatch(); lease.executeBatch();
                        }
                    }
                    entry.executeBatch(); profile.executeBatch(); history.executeBatch(); attempt.executeBatch(); lease.executeBatch();
                    if (from == 0) {
                        addHistory(history, "boundary-now", song(0), "MOBILE", NOW);
                        addHistory(history, "boundary-seven", song(0), "TV", NOW - 7 * DAY);
                        addHistory(history, "boundary-six", song(0), "MOBILE", NOW - 6 * DAY);
                        addHistory(history, "boundary-expired", song(0), "MOBILE", NOW - 7 * DAY - 1);
                        addHistory(history, "boundary-future", song(0), "MOBILE", NOW + 1);
                        addHistory(history, "boundary-admin", song(0), "ADMIN", NOW);
                        history.executeBatch();
                    }
                }
                try (var deleting = connection.prepareStatement("UPDATE song_cache_entry SET state='DELETING' WHERE id=?")) {
                    deleting.setString(1, id(total - 1)); deleting.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(true); }
        }
    }

    private static void addHistory(PreparedStatement history, String id, String song, String type, long at) throws Exception {
        history.setString(1, id); history.setString(2, song); history.setString(3, type);
        history.setLong(4, at - 30_000); history.setLong(5, at); history.setLong(6, at); history.addBatch();
    }

    private static void writeSingleMediaFixture(Path directory) throws Exception {
        Path folder = Files.createDirectory(directory.resolve(id(0)));
        Files.writeString(folder.resolve(".singverse-cache-owner"), OWNER);
        Files.writeString(folder.resolve("master.m3u8"), "#EXTM3U\n");
        Files.write(folder.resolve("stream_v0_0.ts"), new byte[] {42});
    }

    private static void warmHotPath(SongCacheService service) throws Exception {
        assertThat(service.contains(id(0))).isTrue();
        assertThat(service.renew(SESSION)).isNotNull();
        var response = new MockHttpServletResponse();
        assertThat(service.serve(SESSION, "stream_v0_0.ts", null, false, response)).isTrue();
        assertThat(response.getContentAsByteArray()).containsExactly((byte) 42);
    }

    private static Map<String, Integer> hotFileChecks(SongCacheService service) throws Exception {
        var checks = new HashMap<String, Integer>();
        var response = new MockHttpServletResponse();
        boolean contains, served;
        Object renewed;
        // Scoped to the current test thread after cold validation; every intercepted Files call executes normally.
        try (var ignored = mockStatic(Files.class, invocation -> {
            String name = invocation.getMethod().getName();
            if (List.of("isSymbolicLink", "isDirectory", "isRegularFile", "readAttributes", "size",
                    "newInputStream", "newByteChannel", "walk", "list").contains(name)) checks.merge(name, 1, Integer::sum);
            return invocation.callRealMethod();
        })) {
            contains = service.contains(id(0));
            renewed = service.renew(SESSION);
            served = service.serve(SESSION, "stream_v0_0.ts", null, false, response);
        }
        assertThat(contains).isTrue(); assertThat(renewed).isNotNull(); assertThat(served).isTrue();
        assertThat(response.getContentAsByteArray()).containsExactly((byte) 42);
        return Map.copyOf(checks);
    }

    private void writeReport(List<Map<String, Object>> measurements) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("measuredAt", Instant.now().toString()); report.put("fixedPolicyTime", CLOCK.instant().toString());
        report.put("os", System.getProperty("os.name")); report.put("osVersion", System.getProperty("os.version"));
        report.put("architecture", System.getProperty("os.arch")); report.put("javaVersion", System.getProperty("java.version"));
        report.put("javaVendor", System.getProperty("java.vendor"));
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        try (var connection = database.getConnection()) { report.put("sqliteVersion", connection.getMetaData().getDatabaseProductVersion()); }
        report.put("measurements", measurements);
        report.put("limitations", List.of("Isolated local SQLite and migrated production XML, not NAS/MySQL throughput or cold disk IO.",
                "Bulk records have no media files or media-song rows; service list includes exactly 20 real missing-song lookups.",
                "One tiny HLS fixture only; Files counts cover current-thread hot contains/renew/serve after cold worker validation.",
                "Heap delta is an approximate unforced-GC snapshot, may be negative, and includes other JVM activity; allocation is current thread only (-1 if unavailable).",
                "One candidate-load/rank observation per scale and seven mapper-page observations; no latency threshold or statistical performance claim."));
        Path defaultReportPath = Path.of(System.getProperty("java.io.tmpdir"), "singverse-cache-scale-metrics.json");
        Path reportPath = Path.of(System.getProperty("singverse.cache-scale.report", defaultReportPath.toString()));
        Files.writeString(reportPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println("SQLITE_CACHE_SCALE_REPORT " + reportPath);
    }

    private static String id(int index) { return new UUID(0, index + 1L).toString(); }
    private static String song(int index) { return "cache-scale-song-" + index; }
    private static long number(Map<String, Object> row, String field) { return row.get(field) instanceof Number value ? value.longValue() : 0; }
    private static Long nullable(Map<String, Object> row, String field) { return row.get(field) instanceof Number value ? value.longValue() : null; }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static long allocated() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()
                && allocation.isThreadAllocatedMemoryEnabled() ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }
    private static long allocationDelta(long before, long after) { return before < 0 || after < 0 ? -1 : after - before; }

    static final class SqlCounts {
        int statements;
        final Map<String, Integer> queries = new LinkedHashMap<>();
    }
    @Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
    })
    static final class SqlCounter implements Interceptor {
        private final ThreadLocal<SqlCounts> current = new ThreadLocal<>();
        void begin() { current.set(new SqlCounts()); }
        SqlCounts end() { var result = current.get(); current.remove(); return result == null ? new SqlCounts() : result; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var counts = current.get();
            if (counts != null) {
                if (invocation.getTarget() instanceof StatementHandler) counts.statements++;
                else counts.queries.merge(((MappedStatement) invocation.getArgs()[0]).getId(), 1, Integer::sum);
            }
            return invocation.proceed();
        }
    }
}
