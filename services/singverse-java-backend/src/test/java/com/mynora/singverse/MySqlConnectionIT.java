package com.mynora.singverse;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("mysql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlConnectionIT {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path temp;

    @org.springframework.test.context.DynamicPropertySource
    static void isolatedFiles(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        properties.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        properties.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        properties.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        properties.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        properties.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        properties.add("singverse.playback-activity.worker-enabled", () -> false);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TestRestTemplate http;
    @Autowired com.mynora.singverse.media.audio.AudioProcessingMapper audio;
    @Autowired com.mynora.singverse.media.audio.AudioRoleMapper audioRoles;
    @Autowired com.mynora.singverse.media.transcoding.SongCacheMapper cache;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSessions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.mynora.singverse.media.playbackhistory.PlaybackActivityService activity;
    @Autowired com.mynora.singverse.media.playbackhistory.PlaybackActivityMapper activityMapper;
    @Autowired com.mynora.singverse.background.BackgroundTaskService backgroundTasks;
    @Autowired com.mynora.singverse.background.BackgroundTaskMapper backgroundMapper;
    @Autowired com.mynora.singverse.media.metadata.MetadataMapper metadataTasks;
    @Autowired com.mynora.singverse.media.download.LxMapper lx;

    @Test void lxDownloadBindingsQueuesAssetsAndProjectionWorkOnRealMysql() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var id=java.util.UUID.randomUUID().toString();var script=java.util.UUID.randomUUID().toString();var batch=java.util.UUID.randomUUID().toString();var item=java.util.UUID.randomUUID().toString();var now=java.time.Instant.now().toString();
            java.util.function.Function<Object[],java.util.Map<String,Object>> a=com.mynora.singverse.media.download.LxTypes::args;
            jdbc.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,'LOCAL','/isolated-lx',?,'',?,?)",id,"MySQL 下载",id,now,now);
            lx.lockSettings();lx.bind(java.util.Map.of("sourceId",id));assertThat(lx.settings()).containsEntry("source_id",id);
            lx.insertScript(java.util.Map.of("id",script,"name","隔离音源","version","1","environment","mobile","hash",script,"secret","test-only-ciphertext","capabilities","{\"wy\":[\"128k\"]}","now",now));lx.activate(java.util.Map.of("id",script));
            String trackKey="a".repeat(64),trackJson="{\"id\":\""+trackKey+"\",\"platform\":\"wy\",\"remoteId\":\"101\",\"title\":\"MySQL 歌曲\",\"artists\":[\"歌手\"],\"album\":\"专辑\",\"durationMs\":1000,\"coverUrl\":\"\",\"musicInfo\":{}}";
            lx.insertTrack(java.util.Map.of("id",trackKey,"platform","wy","remoteId","101","json",trackJson,"now",now));assertThat(lx.track(java.util.Map.of("id",trackKey))).containsEntry("remote_id","101");
            lx.insertBatch(java.util.Map.of("id",batch,"name","MySQL 下载批次","sourceId",id,"path","/isolated-lx","scriptId",script,"quality","auto","now",now));
            lx.insertItem(a.apply(new Object[]{"id",item,"batchId",batch,"trackKey",trackKey,"trackJson",trackJson,"quality","auto","activeKey",id+":"+trackKey+":auto","status","QUEUED","message","","songId",null,"now",now}));
            assertThat(lx.activeSource(java.util.Map.of("sourceId",id))).isOne();assertThat(lx.activeItem(java.util.Map.of("activeKey",id+":"+trackKey+":auto"))).containsEntry("id",item);
            lx.claim(java.util.Map.of("id",item,"now",now));lx.reserveQuality(java.util.Map.of("id",item,"quality","128k","key",id+":"+trackKey+":128k","now",now));assertThat(lx.qualityOwner(java.util.Map.of("key",id+":"+trackKey+":128k"))).containsEntry("id",item);
            lx.progress(java.util.Map.of("id",item,"received",100,"total",100,"now",now));
            lx.insertSong(a.apply(new Object[]{"songId",item,"sourceId",id,"relativePath","song.wav","pathHash",item,"size",100,"modified",now,"now",now,"profileId","default","revision",0,"result","{}"}));
            lx.insertAsset(a.apply(new Object[]{"id",trackKey,"sourceId",id,"trackKey",trackKey,"quality","128k","relativePath","song.wav","songId",item,"size",100,"modified",now,"hash",trackKey,"trackJson",trackJson,"now",now}));
            assertThat(lx.asset(java.util.Map.of("sourceId",id,"trackKey",trackKey,"quality","128k"))).containsEntry("modified_at",now);assertThat(lx.assetsForTrack(java.util.Map.of("trackKey",trackKey))).hasSize(1);
            lx.cancel(java.util.Map.of("id",batch,"now",now));assertThat(lx.qualityOwner(java.util.Map.of("key",id+":"+trackKey+":128k"))).isNull();assertThat(lx.retryItems(java.util.Map.of("id",batch))).hasSize(1);
            lx.updateBatch(a.apply(new Object[]{"id",batch,"status","CANCELLED","now",now,"started",now,"finished",now}));
            var page=backgroundTasks.list("ALL","LX_DOWNLOAD",null,batch,0,20);assertThat(((Number)page.get("totalElements")).intValue()).isEqualTo(1);
            tx.setRollbackOnly();
        });
    }

    @Test void backgroundTaskProjectionPaginationAndDuplicateImportWorkOnRealMysql() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            String business = java.util.UUID.randomUUID().toString(), now = java.time.Instant.now().toString();
            metadataTasks.insertBatch(business, "MISSING", "[]", false, .9, 0, now);
            metadataTasks.insertItem(business, business, business, "fingerprint", "MySQL 任务", "[]", now);
            metadataTasks.itemStatus(business, "PENDING", "FAILED", "[]", "已有结果", now);
            metadataTasks.batchStatus(business, "RUNNING", "COMPLETED", now);
            var page = backgroundTasks.list("HISTORY", "METADATA", "PARTIAL", business, 0, 20);
            assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(1);
            var row = ((java.util.List<java.util.Map<String, Object>>) page.get("items")).getFirst();
            String taskId = row.get("id").toString();
            assertThat(backgroundTasks.detail(taskId)).containsEntry("status", "PARTIAL");
            assertThat(((Number) backgroundTasks.items(taskId, "FAILED", 0, 20).get("totalElements")).longValue()).isEqualTo(1);
            var snapshot = backgroundMapper.find(java.util.Map.of("id", taskId));
            sqlSessions.insert("com.mynora.singverse.background.BackgroundTaskMapper.insertProjected", snapshot);
            sqlSessions.insert("com.mynora.singverse.background.BackgroundTaskMapper.insertProjected", snapshot);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM background_task WHERE id=?", Integer.class, taskId)).isEqualTo(1);
            tx.setRollbackOnly();
        });
    }

    @Test void batchScopeChainSortingReuseAndChildPagingWorkOnRealMysql() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            String source = java.util.UUID.randomUUID().toString(), song = java.util.UUID.randomUUID().toString();
            String now = java.time.Instant.now().toString(), prefix = "mysql-batch-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16);
            long millis = System.currentTimeMillis();
            jdbc.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,'LOCAL','/isolated',?,'',?,?)", source, "MySQL 隔离批次", source, now, now);
            jdbc.update("INSERT INTO media_song(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,parser_revision,result_json,needs_review) VALUES(?,?,'song.mkv',?,100,?,?,'default',0,'{}',0)", song, source, song, now, now);
            String batch = backgroundTasks.startLocal(source);
            backgroundTasks.registerScanScope(batch, source);
            String fingerprint = jdbc.queryForObject("SELECT fingerprint FROM background_scan_scope WHERE batch_id=?", String.class, batch);
            for (int i = 0; i < 25; i++) {
                String job = prefix + "-" + i;
                audioRoles.insertJob(java.util.Map.of("id", job, "song_id", song, "kind", "PROBE", "state", "QUEUED", "fingerprint", fingerprint, "node_origin", "isolated", "backend", "", "created_at", millis + i, "updated_at", millis + i));
                backgroundTasks.audioSubmitted(job, "PROBE", song, fingerprint, java.util.List.of(batch), true);
            }
            backgroundTasks.finishLocal(batch, new com.mynora.singverse.media.dto.MediaSongDtos.Scan(source, "default", "默认", 0, 1, 1, 0, 0, now), null);
            audioRoles.updateJob(prefix + "-0", "RUNNING", "{}", "", millis + 30);
            assertThat(backgroundTasks.detail(batch)).containsEntry("status", "RUNNING");
            var children = backgroundTasks.children(batch, "AUDIO_JOB", null, 0, 20);
            assertThat(((Number) children.get("totalElements")).intValue()).isEqualTo(25);
            var first = ((java.util.List<java.util.Map<String,Object>>) children.get("items")).getFirst();
            assertThat(first.get("businessId")).isEqualTo(prefix + "-0");
            assertThat(((java.util.List<?>) backgroundTasks.children(batch, null, null, 1, 20).get("items"))).hasSize(5);
            assertThat(((Number) backgroundTasks.list("ACTIVE", null, null, prefix, 0, 20).get("totalElements")).intValue()).isOne();
            for (int i = 0; i < 25; i++) audioRoles.updateJob(prefix + "-" + i, "SUCCEEDED", "{\"streams\":[{},{}]}", "", millis + 40);
            assertThat(backgroundTasks.detail(batch)).containsEntry("status", "QUEUED").containsEntry("finishedAt", null);
            backgroundTasks.resolveAudioScopes(song, fingerprint, "SKIPPED", "无需后续处理");
            assertThat(backgroundTasks.detail(batch)).containsEntry("status", "COMPLETED");
            var row = backgroundMapper.find(java.util.Map.of("id", batch));
            assertThat(((Number) row.get("batch_expires_at")).longValue() - ((Number) row.get("batch_finished_at")).longValue()).isEqualTo(30L * 86400_000);

            String oldJob = prefix + "-legacy";
            long oldUpdate = millis - 25L * 86400_000;
            jdbc.update("INSERT INTO media_audio_job(id,song_id,kind,state,fingerprint,node_origin,result_json,error,created_at,updated_at) VALUES(?,?,'PROBE','RUNNING',?,'isolated','{}','',?,?)",
                    oldJob, song, fingerprint, oldUpdate, oldUpdate);
            audioRoles.updateJob(oldJob, "SUCCEEDED", "{}", "", oldUpdate);
            String legacy = jdbc.queryForObject("SELECT parent_id FROM background_task WHERE type='AUDIO_JOB' AND business_id=?", String.class, oldJob);
            assertThat(backgroundTasks.detail(legacy)).containsEntry("batchKind", "LEGACY").containsEntry("finishedAt", null);
            assertThat(jdbc.queryForObject("SELECT batch_expires_at FROM background_task WHERE id=?", Long.class, legacy))
                    .isEqualTo(oldUpdate + 30L * 86400_000);
            tx.setRollbackOnly();
        });
    }

    @Test void mysqlProfileConnectsToRealMysql() throws Exception {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT version FROM schema_migrations", String.class))
                .containsExactlyInAnyOrder(com.mynora.singverse.infrastructure.database.InitialSchemaFixture.VERSIONS);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        var response = http.getForEntity("/api/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test void newAudioPreparationColumnsAndBulkJobHistoryWorkOnRealMysql() {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            String prefix="ma-"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,20);
            audio.insertBatch(prefix,"",1,2);
            assertThat(audio.batch(prefix)).containsEntry("state","PREPARING");
            assertThat(((Number)audio.batch(prefix).get("selection_total")).longValue()).isEqualTo(2);
            assertThat(audio.ready(prefix,2)).isZero();
            assertThat(audio.lockPreparing(prefix,2)).isEqualTo(1);
            audio.insertItems(java.util.List.of(java.util.Map.of("batch",prefix,"song",prefix+"-a","fingerprint","current","title","测试歌曲","category","UNPROBED","state","PENDING","error","","now",2)));
            audio.prepared(prefix,1,2);
            assertThat(((Number)audio.batch(prefix).get("prepared_count")).longValue()).isEqualTo(1);
            audio.cancel(prefix,3);
            assertThat(audio.lockPreparing(prefix,4)).isZero();
            assertThat(audio.ready(prefix,4)).isZero();
            audio.insertBatch(prefix+"-interrupted","",1,2);
            assertThat(audio.recoverPreparing(5)).isGreaterThanOrEqualTo(1);
            assertThat(audio.batch(prefix+"-interrupted")).containsEntry("state","FAILED");
            assertThat(audio.batch(prefix)).containsEntry("state","CANCELLED");

            for(int i=0;i<25;i++)for(String song:java.util.List.of(prefix+"-a",prefix+"-b"))
                audioRoles.insertJob(java.util.Map.of("id",song+"-"+String.format("%02d",i),"song_id",song,
                        "kind","PROBE","state","SUCCEEDED","fingerprint","current","node_origin","test",
                        "created_at",i,"updated_at",i,"backend",""));
            audioRoles.insertJob(java.util.Map.of("id",prefix+"-stale","song_id",prefix+"-a","kind","PROBE",
                    "state","SUCCEEDED","fingerprint","stale","node_origin","test","created_at",99,"updated_at",99,"backend",""));
            var history=audioRoles.jobsForSongs(java.util.List.of(java.util.Map.of("id",prefix+"-a","fingerprint","current"),java.util.Map.of("id",prefix+"-b","fingerprint","current")));
            assertThat(history).hasSize(40).allMatch(row->"current".equals(row.get("fingerprint")));
            assertThat(history.stream().filter(row->(prefix+"-a").equals(row.get("song_id"))).toList())
                    .hasSize(20).first().extracting(row->row.get("id")).isEqualTo(prefix+"-a-24");
            status.setRollbackOnly();
        });
    }

    @Test void originalCacheBytesTargetAndLeasesRemainTransactionalOnRealMysql() throws Exception {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var nested = new org.springframework.transaction.support.TransactionTemplate(transactions);
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        String id = java.util.UUID.randomUUID().toString();
        String session = java.util.UUID.randomUUID().toString();
        String key = java.util.UUID.randomUUID().toString().replace("-", "");
        long firstBytes = 2_147_483_648L, nextBytes = 4_294_967_296L, sourceBytes = 9_000_000_000L;
        var entry = new java.util.HashMap<String, Object>();
        entry.put("id", id); entry.put("cache_key", key); entry.put("song_id", "mysql-original-" + id);
        entry.put("execution_json", "{}"); entry.put("artifacts_json", "{}"); entry.put("media_type", "VIDEO");
        var targetConfiguration = json.createObjectNode().put("mode", "ORIGINAL").put("sourceBytes", sourceBytes)
                .put("blockBytes", com.mynora.singverse.media.transcoding.SongCacheService.ORIGINAL_BLOCK_BYTES);
        entry.put("target_json", targetConfiguration.toString());
        entry.put("bytes", 0L); entry.put("created_at", 100L);
        var saved = transaction.execute(status -> {
            assertThat(cache.insert(entry)).isEqualTo(1);
            assertThat(cache.addBytes(id, firstBytes, 200)).isEqualTo(1);
            assertThat(cache.addBytes(id, nextBytes, 300)).isEqualTo(1);
            assertThat(cache.target(id, targetConfiguration.put("rangeSupported", true).toString())).isEqualTo(1);
            assertThat(((Number) cache.byKey(key).get("bytes")).longValue()).isEqualTo(firstBytes + nextBytes);
            assertThat(((Number) cache.find(id).get("last_access_at")).longValue()).isEqualTo(300);
            assertThat(cache.bind(session, id, 400)).isEqualTo(1);
            assertThat(cache.renew(session, 500)).isEqualTo(1);
            assertThat(cache.active(id, 499)).isEqualTo(1);
            assertThat(cache.active(id, 500)).isZero();
            nested.executeWithoutResult(deletion -> {
                assertThat(cache.delete(id)).isEqualTo(1);
                assertThat(cache.find(id)).isNull();
                assertThat(cache.lease(session)).isNull();
                deletion.setRollbackOnly();
            });
            // A JDBC savepoint rollback does not invalidate MyBatis' first-level query cache.
            sqlSessions.clearCache();
            assertThat(((Number) cache.find(id).get("bytes")).longValue()).isEqualTo(firstBytes + nextBytes);
            assertThat(cache.lease(session)).containsEntry("cache_id", id);
            assertThat(cache.active(id, 499)).isEqualTo(1);
            status.setRollbackOnly();
            return cache.byKey(key);
        });
        assertThat(saved).containsEntry("id", id).containsEntry("media_type", "VIDEO");
        var target = json.readTree(saved.get("target_json").toString());
        assertThat(target.path("mode").asText()).isEqualTo("ORIGINAL");
        assertThat(target.path("sourceBytes").asLong()).isEqualTo(sourceBytes);
        assertThat(target.path("blockBytes").asLong()).isEqualTo(com.mynora.singverse.media.transcoding.SongCacheService.ORIGINAL_BLOCK_BYTES);
        assertThat(target.path("rangeSupported").asBoolean()).isTrue();
        assertThat(cache.find(id)).isNull();
        assertThat(cache.lease(session)).isNull();
    }

    @Test void realPlaybackProfilesAndAttemptRowsRemainTransactionalOnMysql() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        String id = java.util.UUID.randomUUID().toString(), song = "activity-" + id;
        transaction.executeWithoutResult(status -> {
            activityMapper.ensureProfile(song);
            assertThat(activityMapper.lockProfile(song)).isOne();
            activityMapper.updateProfile(song, 1000L, 0.5, 900L);
            var profile = activityMapper.currentProfile(song, true);
            assertThat(((Number) profile.get("heat")).doubleValue()).isEqualTo(0.5);
            assertThat(((Number) profile.get("pinned")).intValue()).isZero();
            var row = new java.util.HashMap<String, Object>();
            row.put("id", id); row.put("song_id", song); row.put("client_type", "MOBILE");
            row.put("owner_session_id", "isolated-owner"); row.put("token_hash", "test-only-hash");
            row.put("snapshot_json", "{}"); row.put("started_at", 1000L); row.put("last_event_at", 1000L); row.put("lease_until", 121000L);
            assertThat(activityMapper.insertAttempt(row)).isOne();
            assertThat(activityMapper.currentAttempt(id, true)).containsEntry("state", "PREPARING");
            String current = java.util.UUID.randomUUID().toString();
            for (int i = 1; i <= 3; i++) jdbc.update("INSERT INTO playback_history(id,song_id,title,artists_json,client_type,"
                            + "started_at,played_ms,status,effective_at,heat_applied,activity_prepared) VALUES(?,?,'乱序热度','[]','MOBILE',0,30000,'STOPPED',?,?,1)",
                    i == 2 ? current : java.util.UUID.randomUUID().toString(), song, i * 1000L, i == 1 ? 1 : 0);
            assertThat(activityMapper.appliedHeatTimes(song, current, true)).containsExactly(1000L, 2000L);
            status.setRollbackOnly();
        });
        assertThat(activityMapper.profile(song)).isNull();
        assertThat(activityMapper.attempt(id)).isNull();
    }

    @Test void initialDefaultsAndPlaybackConstraintsWorkOnRealMysql() {
        for (var expected : java.util.Map.of("policy", "HYBRID", "revision", "0", "high_watermark_percent", "90",
                "low_watermark_percent", "75").entrySet()) {
            assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE()"
                    + " AND table_name='song_cache_settings' AND column_name=?", String.class, expected.getKey()))
                    .as("initial default for %s", expected.getKey()).isEqualTo(expected.getValue());
        }
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='playback_activity_state' AND column_name='ready'", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='playback_activity_state' AND column_name='phase'", String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForList("SELECT id FROM ai_feature ORDER BY id", String.class))
                .containsExactly("assistant", "classification", "default-search");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        String song = "baseline-" + java.util.UUID.randomUUID(), attempt = java.util.UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO song_playback_profile(song_id) VALUES(?)", song);
            var profile = jdbc.queryForMap("SELECT last_played_at,heat,heat_updated_at,pinned,pin_revision FROM song_playback_profile WHERE song_id=?", song);
            assertThat(profile).containsEntry("last_played_at", null).containsEntry("heat_updated_at", null);
            assertThat(((Number) profile.get("heat")).doubleValue()).isZero();
            assertThat(((Number) profile.get("pinned")).intValue()).isZero();
            assertThat(((Number) profile.get("pin_revision")).longValue()).isZero();
            assertCheckViolation(() -> jdbc.update("UPDATE song_playback_profile SET heat=1.01 WHERE song_id=?", song));
            assertCheckViolation(() -> jdbc.update("UPDATE song_playback_profile SET pinned=2 WHERE song_id=?", song));
            jdbc.update("INSERT INTO playback_attempt(id,client_type,owner_session_id,song_id,snapshot_json,state,started_at,last_event_at,lease_until)"
                    + " VALUES(?,'MOBILE','baseline-owner',?,'{}','PREPARING',1000,1000,121000)", attempt, song);
            var row = jdbc.queryForMap("SELECT event_seq,actual_played_ms,elapsed_ms,client_started_at,client_last_activity_at,last_activity_at,duration_ms"
                    + " FROM playback_attempt WHERE id=?", attempt);
            assertThat(((Number) row.get("event_seq")).longValue()).isEqualTo(-1);
            assertThat(((Number) row.get("actual_played_ms")).longValue()).isZero();
            assertThat(((Number) row.get("elapsed_ms")).longValue()).isZero();
            assertThat(row).containsEntry("client_started_at", null).containsEntry("client_last_activity_at", null)
                    .containsEntry("last_activity_at", null).containsEntry("duration_ms", null);
            assertCheckViolation(() -> jdbc.update("UPDATE playback_attempt SET actual_played_ms=-1 WHERE id=?", attempt));
            assertCheckViolation(() -> jdbc.update("UPDATE playback_attempt SET client_type='UNKNOWN' WHERE id=?", attempt));
            status.setRollbackOnly();
        });
    }

    private static void assertCheckViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        // Connector/J reports CHECK rejection as HY000/3819, outside Spring's integrity category.
        var failure = org.assertj.core.api.Assertions.catchThrowable(operation);
        org.assertj.core.api.Assertions.assertThat(failure)
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        var sql = (java.sql.SQLException) failure.getCause();
        assertThat(sql.getErrorCode()).isEqualTo(3819);
        assertThat(sql.getSQLState()).isEqualTo("HY000");
    }

    @Test void hybridWatermarksPinCasPlaybackAggregatesAndDeletionJournalWorkOnRealMysql() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                + " AND table_name='song_cache_entry' AND index_name='song_cache_page'", Integer.class)).isEqualTo(2);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        String id = java.util.UUID.randomUUID().toString(), song = "hybrid-" + id;
        long now = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
            var settings = cache.settings();
            long revision = ((Number) settings.get("revision")).longValue();
            var updated = java.util.Map.<String,Object>of("maxBytes", 67108864L, "policy", "HYBRID", "ttlHours", 168,
                    "highWatermarkPercent", 92, "lowWatermarkPercent", 70, "revision", revision);
            assertThat(cache.saveSettings(updated)).isOne();
            assertThat(cache.saveSettings(updated)).isZero();
            assertThat(((Number) cache.settings().get("low_watermark_percent")).intValue()).isEqualTo(70);
            assertThat(cache.ensureProfile(song)).isOne();
            assertThat(cache.lockProfile(song)).isOne();
            assertThat(cache.pin(song, 1, 0)).isOne();
            assertThat(cache.pin(song, 0, 0)).isZero();
            var entry = new java.util.HashMap<String,Object>();
            entry.put("id", id); entry.put("song_id", song); entry.put("cache_key", "hybrid-" + id);
            entry.put("execution_json", "{}"); entry.put("artifacts_json", "{}");
            entry.put("target_json", "{\"mode\":\"ORIGINAL\",\"sourceBytes\":16}");
            entry.put("media_type", "VIDEO"); entry.put("bytes", 16); entry.put("created_at", now);
            assertThat(cache.insert(entry)).isOne();
            long committed = ((Number) cache.totals().get("media_bytes")).longValue();
            assertThat(cache.repairOriginalBytes(id, 8, 8, now)).isOne();
            assertThat(((Number) cache.find(id).get("bytes")).longValue()).isEqualTo(8);
            assertThat(((Number) cache.find(id).get("residue_bytes")).longValue()).isEqualTo(8);
            assertThat(((Number) cache.totals().get("media_bytes")).longValue()).isEqualTo(committed);
            jdbc.update("INSERT INTO playback_history(id,song_id,title,artists_json,client_type,started_at,played_ms,status,"
                            + "effective_at,heat_applied,activity_prepared,last_activity_at) VALUES(?,?,'混合评分测试','[]','MOBILE',?,30000,'STOPPED',?,1,1,?)",
                    java.util.UUID.randomUUID().toString(), song, now - 30000, now, now);
            String attempt = java.util.UUID.randomUUID().toString();
            jdbc.update("INSERT INTO playback_attempt(id,client_type,owner_session_id,song_id,snapshot_json,state,started_at,"
                            + "started_playing_at,last_event_at,lease_until) VALUES(?,'MOBILE','isolated-owner',?,'{}','BUFFERING',?,?,?,?)",
                    attempt, song, now - 30000, now - 30000, now, now + 120000);
            assertThat(cache.playing(song, now)).isOne();
            var candidate = cache.candidates(now - 604800000L, now).stream()
                    .filter(row -> id.equals(row.get("id"))).findFirst().orElseThrow();
            assertThat(((Number) candidate.get("plays_7d")).longValue()).isOne();
            assertThat(((Number) candidate.get("playing_count")).longValue()).isOne();
            assertThat(((Number) candidate.get("pinned")).longValue()).isOne();
            assertThat(cache.pin(song, 0, 1)).isOne();
            jdbc.update("DELETE FROM playback_attempt WHERE id=?", attempt);
            assertThat(cache.playing(song, now)).isZero();
            String token = java.util.UUID.randomUUID().toString();
            assertThat(cache.claimDelete(id, token)).isOne();
            assertThat(cache.claimDelete(id, token)).isZero();
            assertThat(cache.deletingForSong(song)).isOne();
            assertThat(cache.find(id)).containsEntry("state", "DELETING").containsEntry("delete_token", token);
            assertThat(cache.failedDelete(id, now + 60000, "controlled retry")).isOne();
            assertThat(cache.pendingDeletes(now).stream().noneMatch(row -> id.equals(row.get("id")))).isTrue();
            assertThat(cache.pendingDeletes(now + 60000).stream().anyMatch(row -> id.equals(row.get("id")))).isTrue();
            status.setRollbackOnly();
        });
        assertThat(cache.find(id)).isNull();
        assertThat(cache.profile(song)).isNull();
    }

    @Test void concurrentSameSequenceReportsOnMysqlAddExactlyOneEffectivePlayAndHeatIncrement() throws Exception {
        String source = java.util.UUID.randomUUID().toString(), song = java.util.UUID.randomUUID().toString();
        String now = java.time.Instant.now().toString(), owner = "mysql-concurrency-owner";
        String parsed = json.writeValueAsString(new com.mynora.singverse.media.filename.KtvFilenameParser().parse("歌手-并发播放.mp4"));
        jdbc.update("INSERT INTO media_source(id,name,source_type,location,location_hash,username,created_at,updated_at) VALUES(?,?,'LOCAL',?,?,'',?,?)",
                source, "隔离并发来源", temp.toString(), source, now, now);
        jdbc.update("INSERT INTO media_song(id,source_id,relative_path,path_hash,scanned_at,parser_profile_id,parser_revision,result_json,needs_review)"
                + " VALUES(?,?,?, ?,?,'default',0,?,0)", song, source, "song.mp4", song, now, parsed);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            while (!activity.isReady()) activity.backfillBatch();
            long originalStart = System.currentTimeMillis() - 45_000;
            var created = activity.create("MOBILE", owner, song, "并发测试", originalStart);
            String id = created.get("attemptId").toString(), token = created.get("writeToken").toString();
            activity.report("MOBILE", owner, id, json.valueToTree(java.util.Map.of("writeToken", token,
                    "eventSeq", 0, "state", "PLAYING", "actualPlayedMs", 0, "elapsedMs", 0,
                    "occurredAt", originalStart, "lastActivityAt", originalStart)));
            var event = json.valueToTree(java.util.Map.of("writeToken", token, "eventSeq", 1,
                    "state", "PLAYING", "actualPlayedMs", 30000, "elapsedMs", 30000,
                    "occurredAt", originalStart + 30_000, "lastActivityAt", originalStart + 30_000));
            var ready = new java.util.concurrent.CountDownLatch(2);
            var release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<java.util.Map<String,Object>> report = () -> {
                ready.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("concurrency gate timed out");
                return activity.report("MOBILE", owner, id, event);
            };
            var first = workers.submit(report); var second = workers.submit(report);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); release.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).containsEntry("effective", true);
            assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).containsEntry("effective", true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM playback_history WHERE song_id=? AND effective_at IS NOT NULL", Integer.class, song)).isOne();
            assertThat(((Number) activityMapper.profile(song).get("heat")).doubleValue()).isEqualTo(0.05);
            assertThat(((Number) activityMapper.attempt(id).get("event_seq")).longValue()).isEqualTo(1);
            assertThat(((Number) activityMapper.attempt(id).get("effective_at")).longValue()).isEqualTo(originalStart + 30_000);
            assertThat(((Number) activityMapper.profile(song).get("last_played_at")).longValue()).isEqualTo(originalStart + 30_000);
        } finally {
            jdbc.update("DELETE FROM playback_attempt WHERE song_id=?", song);
            jdbc.update("DELETE FROM playback_history WHERE song_id=?", song);
            jdbc.update("DELETE FROM song_playback_profile WHERE song_id=?", song);
            jdbc.update("DELETE FROM media_song WHERE id=?", song);
            jdbc.update("DELETE FROM media_source WHERE id=?", source);
        }
    }
}
