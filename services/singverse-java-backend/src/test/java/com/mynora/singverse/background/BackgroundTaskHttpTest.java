package com.mynora.singverse.background;

import static com.mynora.singverse.background.BackgroundTaskProjector.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.audio.*;
import com.mynora.singverse.media.classification.ClassificationMapper;
import com.mynora.singverse.media.metadata.MetadataMapper;
import com.mynora.singverse.media.transcoding.*;
import com.mynora.singverse.media.webdav.ScanTaskMapper;
import com.mynora.singverse.media.webdav.metadata.WebDavShareJobMapper;
import com.mynora.singverse.infrastructure.database.DatabaseInitializer;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mybatis.spring.SqlSessionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "singverse.metadata.worker-enabled=false", "singverse.classification.worker-enabled=false",
    "singverse.webdav.metadata-worker-enabled=false", "singverse.artists.worker-enabled=false",
    "singverse.media.scan-scheduler-enabled=false", "singverse.webdav.share-worker-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackgroundTaskHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry r) {
        r.add("singverse.sqlite.path", () -> temp.resolve("tasks.db").toString());
        r.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        r.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        r.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        r.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        r.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        r.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        r.add("singverse.compute.base-url", () -> "");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired BackgroundTaskService tasks;
    @Autowired BackgroundTaskMapper mapper;
    @Autowired MetadataMapper metadata;
    @Autowired ClassificationMapper classification;
    @Autowired ScanTaskMapper scans;
    @Autowired AudioProcessingMapper batches;
    @Autowired AudioRoleMapper audio;
    @Autowired TranscodeMapper transcodes;
    @Autowired SongCacheMapper cache;
    @Autowired WebDavShareJobMapper shares;
    @Autowired DataSource database;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SqlSessionTemplate session;
    @Autowired DatabaseInitializer initializer;
    @Autowired BackgroundTaskProjector projector;
    @Autowired AudioJobService jobs;
    @MockitoBean TranscodeClient compute;
    @MockitoBean AudioDispatch dispatch;
    @MockitoBean TranscodeService transcoding;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @Test @Order(1) void businessBoundariesProduceReadOnlySafePaginatedSnapshots() throws Exception {
        var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/background-tasks")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        for (String suffix : List.of("/missing", "/missing/items", "/missing/children")) {
            var rejected = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/background-tasks" + suffix)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(401);
        }
        login();
        seed("source", "WEBDAV", "song");
        seed("local", "LOCAL", "local-song");
        String now = Instant.now().toString();
        long millis = System.currentTimeMillis();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            scans.insert("scan", "source", now); scans.start("scan", now);
            scans.progress("scan", 2, 40, 7, 4, 3, 33);
            metadata.insertBatch("meta", "MISSING", "[]", false, .9, 0, now);
            for (int i = 0; i < 25; i++) {
                String item = "meta-" + String.format("%02d", i);
                metadata.insertItem(item, "meta", "song", "fp", "歌曲" + i, "[]", now);
                metadata.itemStatus(item, "PENDING", i == 0 ? "FAILED" : "REVIEW", "[]",
                        i == 0 ? "请求 http://10.1.2.3/private 失败 token=demo-secret /srv/media/private/song.mkv" : "", now);
            }
            metadata.batchStatus("meta", "RUNNING", "COMPLETED", now);
            classification.insertTask("classify", "{\"apiKey\":\"never-return-config\"}", "[]", "[]", "[]", now);
            classification.insertItem("classify-item", "classify", "song", "{\"title\":\"分类歌曲\"}", "v1", 0, now);
            classification.taskState("classify", "PAUSED", now, now);
            batches.insertBatch("batch", "", millis, 1);
            batches.insertItems(List.of(args("batch", "batch", "song", "song", "fingerprint", "fp", "title", "批量歌曲",
                    "category", "ELIGIBLE", "state", "PENDING", "error", "", "now", millis)));
            batches.prepared("batch", 1, millis); batches.ready("batch", millis); batches.start("batch", millis);
            audio.insertJob(args("id", "child", "song_id", "song", "kind", "SEPARATE", "state", "QUEUED",
                    "fingerprint", "fp", "node_origin", "http://10.1.2.3/internal", "backend", "", "created_at", millis, "updated_at", millis));
            tasks.audioSubmitted("child", "SEPARATE", "song", "fp", List.of(id("AUDIO_BATCH", "batch")), true);
            batches.updateItem("batch", "song", "PROCESSING", "child", "", millis);
            audio.insertJob(args("id", "single", "song_id", "song", "kind", "FAST", "state", "QUEUED",
                    "fingerprint", "fp", "node_origin", "", "backend", "", "created_at", millis, "updated_at", millis));
            audio.insertJob(args("id", "automatic-tags", "song_id", "song", "kind", "PROBE", "state", "QUEUED",
                    "fingerprint", "fp", "node_origin", "", "backend", "", "created_at", millis, "updated_at", millis));
            for (String purpose : List.of("OFFLINE", "REALTIME"))
                transcodes.insert(args("id", purpose, "song_id", "song", "owner_id", "admin", "purpose", purpose,
                        "state", "QUEUED", "fingerprint", "fp", "node_origin", "http://internal", "input_json", "{}", "target_json", "{}", "now", millis));
            cache.insertJob(args("id", "cleanup", "mode", "TTL", "state", "SUCCEEDED", "now", millis,
                    "result", "{\"removed\":2,\"failed\":1,\"errors\":[{\"id\":\"cache-id\",\"message\":\"文件被占用\"}]}"));
            shares.insert(args("id", "share", "sourceId", "source", "binding", "private-binding", "baseId", null, "now", millis));
            shares.sourceCount(args("id", "share", "sourceId", "source"));
        });
        String local = tasks.startLocal("local");
        tasks.localProgress(local, "SCANNING", 18, 3, null, null);
        assertThat(node(local).path("progress").path("percent").isNull()).isTrue();
        tasks.localProgress(local, "READING_TAGS", 18, 3, 1L, 3L);
        assertThat(node(local).path("operation").path("progress").path("percent").asDouble()).isCloseTo(100d / 3, within(.01));
        tasks.maintain(); // Also flushes the last coalesced progress when no further business write occurs.
        assertThat(node(id("AUDIO_BATCH", "batch")).path("status").asText()).isEqualTo("QUEUED");
        audio.updateJob("child", "RUNNING", "{\"stage\":\"separate\",\"progress\":{\"completedFrames\":20,\"frames\":100},\"input\":{\"token\":\"secret\"}}", "", millis + 1);
        tasks.maintain();
        var all = get("/api/v1/background-tasks?view=ALL");
        assertThat(all.path("totalElements").asInt()).isEqualTo(9);
        assertThat(all.path("items").findValuesAsText("businessId")).doesNotContain("child", "automatic-tags", "REALTIME");
        assertThat(node(id("AUDIO_BATCH", "batch")).path("status").asText()).isEqualTo("RUNNING");
        assertThat(node(id("AUDIO_JOB", "child")).path("execution").path("progress").path("frames").asInt()).isEqualTo(100);
        var scan = node(id("WEBDAV_SCAN", "scan"));
        assertThat(scan.path("progress").path("total").isNull()).isTrue();
        assertThat(scan.path("statistics").path("entries").asInt()).isEqualTo(40);
        var meta = node(id("METADATA", "meta"));
        assertThat(meta.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(meta.path("statistics").path("review").asInt()).isEqualTo(24);
        assertThat(meta.path("statistics").path("applied").asInt()).isZero();
        var items = get("/api/v1/background-tasks/" + meta.path("id").asText() + "/items");
        assertThat(items.path("items")).hasSize(20);
        assertThat(get("/api/v1/background-tasks/" + meta.path("id").asText() + "/items?page=1").path("items")).hasSize(5);
        assertThat(get("/api/v1/background-tasks/" + meta.path("id").asText() + "/items?status=FAILED").path("totalElements").asInt()).isEqualTo(1);
        assertThat(items.toString()).doesNotContain("10.1.2.3", "demo-secret", "/srv/media/private");
        assertThat(all.toString()).doesNotContain("never-return-config", "private-binding", "node_origin", "http://internal", "secret");
        assertThat(get("/api/v1/background-tasks?type=AUDIO_JOB").path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/background-tasks?view=HISTORY&status=PARTIAL&q=meta").path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/background-tasks?view=ALL&q=%25").path("totalElements").asInt()).isZero();
        assertThat(request("/api/v1/background-tasks?size=101", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request("/api/v1/background-tasks?status=invalid", "GET", null, false).statusCode()).isEqualTo(400);
        assertThat(request("/api/v1/background-tasks/missing", "GET", null, false).statusCode()).isEqualTo(404);

        // Continuation keeps the original record and changes state; a new business id gets a new record.
        String before = node(id("CLASSIFICATION", "classify")).path("createdAt").asText();
        classification.taskState("classify", "RUNNING", now, now);
        classification.taskState("classify", "CANCELLED", now, now);
        assertThat(node(id("CLASSIFICATION", "classify")).path("createdAt").asText()).isEqualTo(before);
        assertThat(node(id("CLASSIFICATION", "classify")).path("status").asText()).isEqualTo("CANCELLED");
        var sql = new JdbcTemplate(database);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task WHERE type='CLASSIFICATION' AND business_id='classify'", Integer.class)).isEqualTo(1);
        var repeated = mapper.find(args("id", id("METADATA", "meta")));
        session.insert(NS + "insertProjected", repeated);
        session.insert(NS + "insertProjected", repeated);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task WHERE business_id='meta'", Integer.class)).isEqualTo(1);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            metadata.insertBatch("rolled-back", "MISSING", "[]", false, .9, 0, now); tx.setRollbackOnly();
        });
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task WHERE business_id='rolled-back'", Integer.class)).isZero();

        // A source/song can disappear without destroying its display record.
        sql.update("DELETE FROM media_song WHERE id='song'");
        assertThat(node(id("TRANSCODE", "OFFLINE")).path("relatedAvailable").asBoolean()).isFalse();
        assertThat(node(id("TRANSCODE", "OFFLINE")).path("subjectName").asText()).isNotBlank();
        // Real restart handling interrupts only the local display record, leaving source data intact.
        tasks.ready();
        assertThat(node(local).path("status").asText()).isEqualTo("INTERRUPTED");
    }

    @Test @Order(2) void previewExpiryAndRetentionKeepSnapshotsWithoutDeletingBusinessData() throws Exception {
        login();
        long now = System.currentTimeMillis(), old = now - Duration.ofDays(40).toMillis();
        batches.insertBatch("expired-preview", "", now - Duration.ofDays(2).toMillis(), 0);
        batches.ready("expired-preview", now);
        assertThat(node(id("AUDIO_BATCH", "expired-preview")).path("status").asText()).isEqualTo("AWAITING_CONFIRMATION");
        batches.collectPreviewItems(now - Duration.ofDays(1).toMillis());
        batches.collectPreviews(now - Duration.ofDays(1).toMillis());
        assertThat(node(id("AUDIO_BATCH", "expired-preview")).path("status").asText()).isEqualTo("EXPIRED");
        var sql = new JdbcTemplate(database);
        // Seed an old still-running business task, as can exist at startup; legacy missing times stay null.
        sql.update("INSERT INTO metadata_batch(id,mode,status,providers_json,auto_apply,threshold,skipped_existing,created_at,updated_at) VALUES('legacy-active','MISSING','PAUSED','[]',0,0.9,0,?,?)",
                Instant.ofEpochMilli(old).toString(), Instant.ofEpochMilli(old).toString());
        mapper.interruptLocal(args("now", now, "expires", now + RETENTION));
        // A fresh service performs the same batched initial import without running any business worker.
        var restarted = new BackgroundTaskService(mapper, projector, session, transactions, initializer);
        try { restarted.maintain(); } finally { restarted.close(); }
        var legacy = node(id("METADATA", "legacy-active"));
        assertThat(legacy.path("startedAt").isNull()).isTrue();
        assertThat(legacy.path("finishedAt").isNull()).isTrue();
        assertThat(legacy.path("timeIncomplete").asBoolean()).isTrue();
        assertThat(legacy.path("status").asText()).isEqualTo("PAUSED");
        metadata.insertItem("legacy-item", "legacy-active", "local-song", "fp", "保留业务明细", "[]", Instant.now().toString());
        metadata.batchStatus("legacy-active", "PAUSED", "COMPLETED", Instant.now().toString());
        assertThat(node(id("METADATA", "legacy-active")).path("finishedAt").isNull()).isFalse();
        var saved = mapper.find(args("id", id("METADATA", "legacy-active")));
        assertThat(number(saved, "expires_at") - number(saved, "finished_at")).isEqualTo(RETENTION);
        sql.update("UPDATE background_task SET finished_at=?,batch_finished_at=?,expires_at=?,batch_expires_at=? WHERE business_id='legacy-active'",
                now - RETENTION - 1, now - RETENTION - 1, now - 1, now - 1);
        // Even if created long ago, an unfinished task always has no expiry.
        metadata.insertBatch("still-active", "MISSING", "[]", false, .9, 0, Instant.ofEpochMilli(old).toString());
        tasks.maintain();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task WHERE business_id='legacy-active'", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM metadata_batch WHERE id='legacy-active'", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task_item WHERE id='legacy-item'", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM metadata_item WHERE id='legacy-item'", Integer.class)).isEqualTo(1);
        assertThat(node(id("METADATA", "still-active")).path("status").asText()).isEqualTo("RUNNING");
        assertThat(mapper.find(args("id", id("METADATA", "still-active"))).get("expires_at")).isNull();
    }

    @Test @Order(3) void serverSortsBatchesBeforePagingAndSearchesChildrenWithoutDuplicateRows() throws Exception {
        login();
        seed("batch-source", "LOCAL", "batch-song");
        long now = System.currentTimeMillis();
        String old = Instant.ofEpochMilli(now - Duration.ofDays(2).toMillis()).toString();
        scans.insert("sort-old-running", "batch-source", old); scans.start("sort-old-running", old);
        for (int i = 0; i < 25; i++) {
            String business = "sort-queued-" + i;
            batches.insertBatch(business, "", now + i, 1);
            batches.insertItems(List.of(args("batch", business, "song", "batch-song", "fingerprint", "fp", "title", "排序歌曲", "category", "ELIGIBLE", "state", "PENDING", "error", "", "now", now)));
            batches.prepared(business, 1, now); batches.ready(business, now); batches.start(business, now);
        }
        var first = get("/api/v1/background-tasks?q=sort-&size=20");
        assertThat(first.path("totalElements").asInt()).isEqualTo(26);
        assertThat(first.path("items").get(0).path("businessId").asText()).isEqualTo("sort-old-running");
        assertThat(get("/api/v1/background-tasks?q=sort-&page=1").path("items")).hasSize(6);

        var sql = new JdbcTemplate(database);
        String batch = tasks.startLocal("batch-source");
        for (int i = 1; i < 30; i++) sql.update("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,'batch-source',?,?,100,?,?,'default',0,?,0)",
                "bulk-song-" + i, "bulk-" + i + ".mkv", "bulk-" + i, old, old, json.valueToTree(new com.mynora.singverse.media.filename.KtvFilenameParser().parse("歌手-批次歌曲.mkv")).toString());
        tasks.registerScanScope(batch, "batch-source");
        assertThat(get("/api/v1/background-tasks?type=LOCAL_SCAN&q=batch-song").path("totalElements").asInt()).isEqualTo(1);
        var scope = tasks.pendingScopes("", "");
        assertThat(scope.stream().filter(row -> batch.equals(row.get("batch_id"))).toList()).hasSize(30);
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            int index = 0;
            for (var song : sql.queryForList("SELECT song_id,fingerprint FROM background_scan_scope WHERE batch_id=? ORDER BY song_id", batch)) {
                String job = "bulk-probe-" + index++;
                audio.insertJob(args("id", job, "song_id", song.get("song_id"), "kind", "PROBE", "state", "QUEUED", "fingerprint", song.get("fingerprint"), "node_origin", "internal-test", "backend", "", "created_at", now, "updated_at", now));
                tasks.audioSubmitted(job, "PROBE", song.get("song_id").toString(), song.get("fingerprint").toString(), List.of(batch), true);
            }
        });
        audio.updateJob("bulk-probe-0", "RUNNING", "{}", "", now + 1);
        tasks.finishLocal(batch, new com.mynora.singverse.media.dto.MediaSongDtos.Scan("batch-source", "default", "默认", 0, 30, 30, 0, 0, old), null);
        assertThat(node(batch).path("status").asText()).isEqualTo("RUNNING");
        assertThat(node(batch).at("/operation/status").asText()).isEqualTo("COMPLETED");
        assertThat(get("/api/v1/background-tasks?q=bulk-probe").path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/background-tasks/" + batch + "/children").path("items")).hasSize(20);
        assertThat(get("/api/v1/background-tasks/" + batch + "/children").at("/items/0/businessId").asText()).isEqualTo("bulk-probe-0");
        assertThat(get("/api/v1/background-tasks/" + batch + "/children?page=1").path("items")).hasSize(10);
        assertThat(get("/api/v1/background-tasks/" + batch + "/children?status=RUNNING&type=AUDIO_JOB").path("totalElements").asInt()).isEqualTo(1);
        for (int i = 0; i < 30; i++) audio.updateJob("bulk-probe-" + i, "SUCCEEDED", "{\"streams\":[{},{}]}", "", now + 2);
        tasks.maintain();
        assertThat(node(batch).path("status").asText()).isEqualTo("QUEUED");
        assertThat(node(batch).path("finishedAt").isNull()).isTrue();
        assertThat(node(batch).at("/childrenCounts/pendingDiscovery").asInt()).isEqualTo(30);
        assertThat(node(batch).at("/progress/percent").isNull()).isTrue();
        tasks.waitingForAudioNode();
        assertThat(node(batch).path("waitingReason").asText()).contains("等待计算节点");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_scan_scope WHERE batch_id=? AND reason LIKE '等待计算节点%'", Integer.class, batch)).isEqualTo(30);
        tasks.skipPendingAudio("计算节点未配置或已停用");
        assertThat(node(batch).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(node(batch).at("/childrenCounts/skipped").asInt()).isEqualTo(30);
    }

    @Test @Order(4) void delayedFastDiscoveryReuseRollbackBackfillAndSharedRetentionKeepBatchIdentity() throws Exception {
        login();
        seed("chain-source", "LOCAL", "chain-song");
        var sql = new JdbcTemplate(database);
        var connection = org.mockito.Mockito.mock(com.mynora.singverse.compute.ComputeNodeDtos.Connection.class);
        org.mockito.Mockito.when(connection.origin()).thenReturn(URI.create("http://compute.test"));
        org.mockito.Mockito.when(compute.connection()).thenReturn(connection);
        org.mockito.Mockito.when(compute.configured()).thenReturn(true);
        org.mockito.Mockito.when(compute.call(connection, "/internal/v1/status", "GET", null)).thenReturn(json.readTree("{\"capabilities\":[\"media-probe\",\"audio-role-fast\"]}"));
        String first = tasks.startLocal("chain-source");
        tasks.registerScanScope(first, "chain-source");
        String fp = sql.queryForObject("SELECT fingerprint FROM background_scan_scope WHERE batch_id=?", String.class, first);
        String probe = jobs.submit("chain-song", "PROBE", "", List.of(first)).path("id").asText();
        var scan = new com.mynora.singverse.media.dto.MediaSongDtos.Scan("chain-source", "default", "默认", 0, 1, 1, 0, 0, Instant.now().toString());
        tasks.finishLocal(first, scan, null);
        audio.updateJob(probe, "SUCCEEDED", "{\"streams\":[{},{}]}", "", System.currentTimeMillis());
        assertThat(node(first).path("status").asText()).isEqualTo("QUEUED");
        assertThat(node(first).path("finishedAt").isNull()).isTrue();
        jobs.discover();
        String fast = sql.queryForObject("SELECT id FROM media_audio_job WHERE song_id='chain-song' AND kind='FAST'", String.class);
        assertThat(node(first).at("/childrenCounts/queued").asInt()).isEqualTo(1);
        audio.updateJob(fast, "RUNNING", "{}", "", System.currentTimeMillis());
        assertThat(node(first).path("status").asText()).isEqualTo("RUNNING");
        audio.updateJob(fast, "SUCCEEDED", "{\"state\":\"UNKNOWN\",\"reason\":\"AMBIGUOUS_VOCALS\"}", "", System.currentTimeMillis());
        assertThat(node(first).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(node(first).at("/childrenCounts/uncertain").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/background-tasks/" + first + "/children").path("totalElements").asInt()).isEqualTo(2);

        String second = tasks.startLocal("chain-source"); tasks.registerScanScope(second, "chain-source");
        assertThat(jobs.submit("chain-song", "PROBE", "", List.of(second)).path("id").asText()).isEqualTo(probe);
        assertThat(node(id("AUDIO_JOB", probe)).path("parentId").asText()).isEqualTo(first);
        assertThat(node(id("AUDIO_JOB", probe)).path("batches")).hasSize(2);
        jobs.discover();
        jobs.discover(); // A newly admitted scope may be before the persisted traversal cursor; the next sweep reuses UNKNOWN.
        tasks.finishLocal(second, scan, null);
        assertThat(node(second).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task_relation WHERE batch_id=?", Integer.class, second)).isEqualTo(2);
        long before = sql.queryForObject("SELECT COUNT(*) FROM media_audio_job", Long.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jobs.submit("chain-song", "ANALYZE", "", List.of(second)); tx.setRollbackOnly();
        });
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM media_audio_job", Long.class)).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task_relation WHERE batch_id=?", Integer.class, second)).isEqualTo(2);
        String manual = jobs.submit("chain-song", "ANALYZE").path("id").asText();
        String manualTask = id("AUDIO_JOB", manual);
        assertThat(node(manualTask).path("isBatch").asBoolean()).isTrue();
        assertThat(get("/api/v1/background-tasks/" + manualTask + "/children").at("/items/0/businessId").asText()).isEqualTo(manual);

        // An expired first batch cannot evict a child still referenced by the second batch.
        long expired = System.currentTimeMillis() - 1;
        sql.update("UPDATE background_task SET batch_finished_at=?,batch_expires_at=? WHERE id=?", expired - RETENTION, expired, first);
        sql.update("UPDATE background_task SET expires_at=? WHERE id IN (?,?)", expired, id("AUDIO_JOB", probe), id("AUDIO_JOB", fast));
        tasks.maintain();
        assertThat(mapper.find(args("id", first))).isNull();
        assertThat(node(id("AUDIO_JOB", probe)).path("businessId").asText()).isEqualTo(probe);
        assertThat(get("/api/v1/background-tasks/" + second + "/children").path("totalElements").asInt()).isEqualTo(2);
        sql.update("UPDATE background_task SET batch_finished_at=?,batch_expires_at=? WHERE id=?", expired - RETENTION, expired, second);
        tasks.maintain();
        assertThat(mapper.find(args("id", id("AUDIO_JOB", probe)))).isNull();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM media_audio_job WHERE id=?", Integer.class, probe)).isEqualTo(1);

        // Old standalone rows have no recoverable operation identity. Keep IDs and group explicitly.
        sql.update("UPDATE background_task SET batch_kind='UNCLASSIFIED' WHERE id=?", manualTask);
        var restarted = new BackgroundTaskService(mapper, projector, session, transactions, initializer);
        try { restarted.maintain(); } finally { restarted.close(); }
        var restored = node(manualTask);
        assertThat(restored.path("isBatch").asBoolean()).isFalse();
        String legacy = restored.at("/batches/0/id").asText();
        assertThat(node(legacy).path("batchKind").asText()).isEqualTo("LEGACY");
        assertThat(node(legacy).path("name").asText()).contains("未记录原始批次");
        assertThat(get("/api/v1/background-tasks?q=" + manual).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test @Order(5) void legacyRecordsWithoutEndTimesKeepTheirOriginalRetentionClock() throws Exception {
        login();
        var sql = new JdbcTemplate(database);
        long now = System.currentTimeMillis(), expiry = now + 5L * 86400_000;
        String taskId = UUID.randomUUID().toString();
        sql.update("INSERT INTO background_task(id,type,business_id,source_id,name,subject_name,status,business_status,stage,progress_unit,statistics_json,error,created_at,updated_at,expires_at,items_available,time_incomplete,fingerprint) VALUES(?,'AUDIO_JOB',?,?,'缺少结束时间的旧任务','','COMPLETED','SUCCEEDED','SUCCEEDED','ITEMS','{}','',?,?,?,0,1,'legacy-missing-time')",
                taskId, "legacy-missing-time-" + taskId, taskId, now - 26L * 86400_000, now - 25L * 86400_000, expiry);
        var restarted = new BackgroundTaskService(mapper, projector, session, transactions, initializer);
        try {
            restarted.maintain();
            String legacy = node(taskId).at("/batches/0/id").asText();
            assertThat(node(legacy).path("finishedAt").isNull()).isTrue();
            assertThat(node(legacy).path("timeIncomplete").asBoolean()).isTrue();
            assertThat(sql.queryForObject("SELECT batch_expires_at FROM background_task WHERE id=?", Long.class, legacy)).isEqualTo(expiry);
            sql.update("UPDATE background_task SET expires_at=? WHERE id=?", now - 1, taskId);
            restarted.maintain();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM background_task WHERE id IN (?,?)", Integer.class, taskId, legacy)).isZero();
        } finally { restarted.close(); }
    }

    private JsonNode node(String id) throws Exception { return get("/api/v1/background-tasks/" + id); }
    private JsonNode get(String path) throws Exception {
        var response = request(path, "GET", null, false);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private void login() throws Exception {
        assertThat(request("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true).statusCode()).isEqualTo(200);
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private HttpResponse<String> request(String path, String method, Object body, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (csrf) request.header("X-CSRF-Token", json.readTree(request("/api/v1/auth/csrf", "GET", null, false).body()).path("token").asText());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void seed(String source, String type, String song) {
        var sql = new JdbcTemplate(database);
        sql.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,?,'/unread-private-root',?,'private-user',?,?)",
                source, "隔离曲库", type, source, Instant.now().toString(), Instant.now().toString());
        sql.update("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,?,'歌曲.mkv',?,100,?,?,'default',0,?,0)",
                song, source, song, Instant.now().toString(), Instant.now().toString(), json.valueToTree(new com.mynora.singverse.media.filename.KtvFilenameParser().parse("歌手-歌曲.mkv")).toString());
    }
}
