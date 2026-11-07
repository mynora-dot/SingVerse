package com.mynora.singverse.media.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real migrated SQLite and production XML; fixture inserts are intentionally isolated JDBC. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaSongProjectionScaleTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("scale.db").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        registry.add("singverse.compute.base-url", () -> "");
    }

    @Autowired DataSource database;
    @Autowired SqlSessionFactory sessions;
    @Autowired MediaSongRepository songs;
    @Autowired ObjectMapper json;

    @Test
    void tenAndHundredThousandSongsKeepPageProjectionToTwoStatements() throws Exception {
        var counter = new StatementCounter();
        sessions.getConfiguration().addInterceptor(counter);
        String parsed = json.writeValueAsString(new KtvFilenameParser().parse("原歌手-歌曲.mkv"));
        int previous = 0;
        for (int total : new int[] {10_000, 100_000}) {
            seed(previous, total, parsed);
            previous = total;
            // Warm the mapper/Jackson paths before measuring. No latency threshold depends on host load.
            songs.search("scale-source", null, null, 0, 20);
            long[] durations = new long[21];
            long allocation = allocated();
            for (int sample = 0; sample < durations.length; sample++) {
                counter.count.set(new AtomicInteger());
                long start = System.nanoTime();
                var page = songs.search("scale-source", null, null, total / 40, 20);
                durations[sample] = System.nanoTime() - start;
                assertThat(counter.count.get().get()).isEqualTo(2);
                counter.count.remove();
                assertThat(page.totalElements()).isEqualTo(total);
                assertThat(page.items()).hasSize(20).allSatisfy(song -> {
                    assertThat(song.metadata().track().artists()).containsExactly("订正歌手");
                    assertThat(song.metadata().origins().get("artists").kind()).isEqualTo("MANUAL");
                });
            }
            long bytes = allocated() - allocation;
            Arrays.sort(durations);
            System.out.printf(Locale.ROOT,
                    "SQLITE_SONG_PAGE songs=%d page_size=20 statements=2 median_ms=%.3f p95_ms=%.3f allocated_bytes_per_page=%d%n",
                    total, durations[10] / 1_000_000.0, durations[19] / 1_000_000.0, bytes / durations.length);
        }
    }

    private void seed(int from, int total, String parsed) throws Exception {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (from == 0) {
                    try (var source = connection.prepareStatement("INSERT INTO media_source "
                            + "(id,name,source_type,location,location_hash,username,created_at,updated_at) "
                            + "VALUES ('scale-source','隔离规模曲库','WEBDAV','http://127.0.0.1/unused','scale-source','',"
                            + "'2026-11-07T00:00:00Z','2026-11-07T00:00:00Z')")) {
                        source.executeUpdate();
                    }
                }
                try (var song = connection.prepareStatement("INSERT INTO media_song "
                        + "(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,"
                        + "parser_revision,result_json,needs_review) VALUES (?,'scale-source',?,?,100,"
                        + "'2026-11-07T00:00:00Z','2026-11-07T00:00:00Z','default',0,?,0)");
                     var override = connection.prepareStatement("INSERT INTO song_artist_override "
                             + "(source_id,path_key,artists_json,revision) VALUES ('scale-source',?,'[\"订正歌手\"]',1)")) {
                    for (int index = from; index < total; index++) {
                        String id = String.format(Locale.ROOT, "scale-%06d", index);
                        song.setString(1, id);
                        song.setString(2, id + ".mkv");
                        song.setString(3, id);
                        song.setString(4, parsed);
                        song.addBatch();
                        override.setString(1, id);
                        override.addBatch();
                        if ((index + 1) % 200 == 0) {
                            song.executeBatch();
                            override.executeBatch();
                        }
                    }
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static long allocated() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean allocation
                        && allocation.isThreadAllocatedMemorySupported()
                ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class StatementCounter implements Interceptor {
        final ThreadLocal<AtomicInteger> count = new ThreadLocal<>();

        @Override public Object intercept(Invocation invocation) throws Throwable {
            var active = count.get();
            if (active != null) active.incrementAndGet();
            return invocation.proceed();
        }
    }
}
