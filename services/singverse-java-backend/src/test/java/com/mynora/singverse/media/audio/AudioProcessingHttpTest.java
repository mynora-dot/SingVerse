package com.mynora.singverse.media.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.filename.KtvFilenameParser;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AudioProcessingHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {
        registry.add("singverse.sqlite.path", () -> temp.resolve("http.db").toString());
        registry.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        registry.add("singverse.metadata.cache-path", () -> temp.resolve("covers").toString());
        registry.add("singverse.webdav.metadata-path", () -> temp.resolve("webdav").toString());
        registry.add("singverse.audio.directory", () -> temp.resolve("audio").toString());
        registry.add("singverse.transcoding.directory", () -> temp.resolve("versions").toString());
        registry.add("singverse.transcoding.cache-directory", () -> temp.resolve("cache").toString());
        registry.add("singverse.compute.base-url", () -> "");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MediaSongRepository songs;
    @Autowired AudioProcessingMapper batches;
    @Autowired AudioRoleMapper audioJobs;
    @Autowired DataSource database;
    @MockitoBean AudioCatalog catalog;
    final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @Test void retryHeadersAcceptedPreparationAndCancellationAreVisibleOverRealHttp() throws Exception {
        assertThat(request("/api/v1/auth/login", "POST",
                Map.of("username", "admin", "password", "admin"), true).statusCode()).isEqualTo(200);

        // Keep an actual, unready production catalog unready while exercising MVC's exception mapping.
        var source = mock(MediaSongRepository.class);
        var gate = new CountDownLatch(1);
        when(source.batchAfter(anyString(), isNull(), eq(200))).thenAnswer(call -> {
            gate.await(); return List.of();
        });
        try (var cold = new AudioCatalog(source, mock(AudioRoleMapper.class), mock(AudioJobService.class))) {
            doAnswer(call -> cold.current()).when(catalog).current();
            var unavailable = request("/api/v1/audio-processing/songs", "GET", null, false);
            assertThat(unavailable.statusCode()).isEqualTo(503);
            assertThat(unavailable.headers().firstValue("Retry-After")).contains("1");
        } finally { gate.countDown(); }

        seed();
        var song = songs.get("http-song");
        var summary = new AudioCatalog.Summary(song.id(), song.sourceId(), "歌曲", List.of("歌手"),
                MediaSongRepository.fingerprint(song), null, "UNKNOWN", "UNPROBED", false);
        doReturn(new AudioCatalog.Snapshot(List.of(summary), Map.of(song.id(), summary), 1, 1))
                .when(catalog).current();
        var accepted = request("/api/v1/audio-processing/batches/preview", "POST",
                Map.of("ids", List.of(song.id()), "all", false, "backend", ""), true);
        assertThat(accepted.statusCode()).withFailMessage(accepted.body()).isEqualTo(202);
        String id = json.readTree(accepted.body()).path("id").asText();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode detail = json.readTree(request("/api/v1/audio-processing/batches/" + id, "GET", null, false).body());
            assertThat(detail.path("state").asText()).isEqualTo("PREVIEW");
            assertThat(detail.path("selectionTotal").asInt()).isEqualTo(1);
            assertThat(detail.path("prepared").asInt()).isEqualTo(1);
            assertThat(detail.path("items")).hasSize(1);
        });

        batches.insertBatch("preparing-http", "", System.currentTimeMillis(), 1);
        var conflict = request("/api/v1/audio-processing/batches/preparing-http/submit", "POST", null, true);
        assertThat(conflict.statusCode()).withFailMessage(conflict.body()).isEqualTo(409);
        var cancelled = request("/api/v1/audio-processing/batches/preparing-http/cancel", "POST", null, true);
        assertThat(cancelled.statusCode()).withFailMessage(cancelled.body()).isEqualTo(200);
        assertThat(json.readTree(cancelled.body()).path("state").asText()).isEqualTo("CANCELLED");
        verifyQueuePriorityAndNoopWrites();
    }

    private void verifyQueuePriorityAndNoopWrites(){
        var ids=new java.util.HashSet<String>();
        for(String kind:List.of("PROBE","FAST","SEPARATE")){
            String id=java.util.UUID.randomUUID().toString();ids.add(id);
            var row=new java.util.HashMap<String,Object>();row.put("id",id);row.put("song_id","http-song");row.put("fingerprint","current");
            row.put("kind",kind);row.put("state","QUEUED");row.put("node_origin","");row.put("created_at",1000L);row.put("updated_at",1000L);
            audioJobs.insertJob(row);
        }
        assertThat(audioJobs.pending().stream().filter(row->ids.contains(row.get("id"))).map(row->row.get("kind")))
                .containsExactly("SEPARATE","FAST","PROBE");
        String job=ids.iterator().next();
        assertThat(audioJobs.jobsByIds(List.of(job))).hasSize(1);
        assertThat(audioJobs.updateJob(job,"FAILED","{}","test",2000)).isEqualTo(1);
        assertThat(audioJobs.updateJob(job,"FAILED","{}","test",3000)).isZero();
        assertThat(((Number)audioJobs.job(job).get("updated_at")).longValue()).isEqualTo(2000);
        String batch=java.util.UUID.randomUUID().toString();batches.insertBatch(batch,"",1000,1);
        batches.insertItems(List.of(Map.of("batch",batch,"song","http-song","fingerprint","current","title","歌曲",
                "category","ELIGIBLE","state","PENDING","error","","now",1000L)));
        assertThat(batches.updateItem(batch,"http-song","PENDING",null,"",2000)).isZero();
        assertThat(batches.updateItem(batch,"http-song","PROCESSING",job,"",2000)).isEqualTo(1);
        assertThat(batches.updateItem(batch,"http-song","PROCESSING",job,"",3000)).isZero();
    }

    private HttpResponse<String> request(String path, String method, Object body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (csrf) builder.header("X-CSRF-Token", json.readTree(
                request("/api/v1/auth/csrf", "GET", null, false).body()).path("token").asText());
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void seed() throws Exception {
        try (var connection = database.getConnection(); var source = connection.prepareStatement(
                "INSERT INTO media_source (id,name,source_type,location,location_hash,username,created_at,updated_at) "
                + "VALUES ('http-source','隔离曲库','WEBDAV','http://127.0.0.1/unused','http-source','',"
                + "'2026-11-07T00:00:00Z','2026-11-07T00:00:00Z')");
             var song = connection.prepareStatement("INSERT INTO media_song "
                     + "(id,source_id,relative_path,path_hash,file_size,modified_at,scanned_at,parser_profile_id,"
                     + "parser_revision,result_json,needs_review) VALUES ('http-song','http-source','歌曲.mkv',"
                     + "'http-song',100,'2026-11-07T00:00:00Z','2026-11-07T00:00:00Z','default',0,?,0)")) {
            source.executeUpdate();
            song.setString(1, json.writeValueAsString(new KtvFilenameParser().parse("歌手-歌曲.mkv")));
            song.executeUpdate();
        }
    }
}
