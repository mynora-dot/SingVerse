package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext
class ArtistHttpTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("artists.db").toString());
        p.add("singverse.auth.key-file", () -> temp.resolve("auth.key").toString());
        p.add("singverse.classification.worker-enabled", () -> false);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired TvMapper db;
    HttpClient admin, guest;

    @BeforeEach
    void clients() {
        admin =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        guest = HttpClient.newHttpClient();
    }

    HttpResponse<String> req(
            HttpClient client, String path, String method, Object body, String token, boolean csrf)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (csrf) {
            String value =
                    json.readTree(req(client, "/api/v1/auth/csrf", "GET", null, null, false).body())
                            .path("token")
                            .asText();
            b.header("X-CSRF-Token", value);
        }
        if (body != null) b.header("Content-Type", "application/json");
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(
            HttpClient client, String path, String method, Object body, String token, boolean csrf)
            throws Exception {
        var r = req(client, path, method, body, token, csrf);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    String device() throws Exception {
        ok(
                admin,
                "/api/v1/auth/login",
                "POST",
                Map.of("username", "admin", "password", "admin"),
                null,
                true);
        var pending =
                ok(
                        guest,
                        "/api/v1/device-authorizations",
                        "POST",
                        Map.of("name", "客厅电视"),
                        null,
                        false);
        String id = pending.path("id").asText(), secret = pending.path("pollSecret").asText();
        assertThat(
                        req(
                                        guest,
                                        "/api/v1/device-authorizations/" + id + "/poll",
                                        "POST",
                                        Map.of("pollSecret", "wrong"),
                                        null,
                                        false)
                                .statusCode())
                .isEqualTo(401);
        ok(
                admin,
                "/api/v1/admin/device-authorizations/" + id + "/approve",
                "POST",
                Map.of("code", pending.path("code").asText()),
                null,
                true);
        var first =
                ok(
                        guest,
                        "/api/v1/device-authorizations/" + id + "/poll",
                        "POST",
                        Map.of("pollSecret", secret),
                        null,
                        false);
        var repeat =
                ok(
                        guest,
                        "/api/v1/device-authorizations/" + id + "/poll",
                        "POST",
                        Map.of("pollSecret", secret),
                        null,
                        false);
        assertThat(repeat.path("token")).isEqualTo(first.path("token"));
        return first.path("token").asText();
    }

    @Autowired com.mynora.singverse.media.repository.MediaSongRepository songRepository;
    @Autowired com.mynora.singverse.media.artist.ArtistMapper artists;
    @Autowired TvLibrary library;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixture;

    String song(String title, List<String> credits) throws Exception {
        String id = UUID.randomUUID().toString(),
                source = UUID.randomUUID().toString(),
                file = id + ".mp4";
        Files.writeString(temp.resolve(file), "0123456789");
        fixture.update(
                "INSERT INTO"
                    + " media_source(id,name,source_type,location,location_hash,username,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                source,
                "歌手库测试",
                "LOCAL",
                temp.toString(),
                source,
                "",
                "2026-10-29T00:00:00Z",
                "2026-10-29T00:00:00Z");
        var parsed =
                new com.mynora.singverse.media.filename.FilenameParseResult(
                        file,
                        file,
                        title,
                        credits,
                        com.mynora.singverse.media.filename.FilenameParseResult.Language.MANDARIN,
                        com.mynora.singverse.media.filename.FilenameParseResult.VersionType.UNKNOWN,
                        null,
                        "mp4",
                        List.of(),
                        1.0,
                        com.mynora.singverse.media.filename.FilenameParseResult.ParseStatus.SUCCESS,
                        com.mynora.singverse.media.filename.FilenameParseResult.RuleId.TITLE_ONLY,
                        List.of(),
                        false,
                        List.of());
        songRepository.replace(
                source,
                List.of(
                        new com.mynora.singverse.media.repository.MediaSongRepository.Stored(
                                id,
                                source,
                                file,
                                id,
                                10L,
                                "2026-10-29T00:00:00Z",
                                "2026-10-29T00:00:00Z",
                                "default",
                                0,
                                null,
                                songRepository.encode(parsed),
                                false)));
        library.invalidate();
        return id;
    }

    Map<String, Object> input(String request, String name, long revision, boolean active) {
        return Map.of(
                "requestId",
                request,
                "name",
                name,
                "aliases",
                List.of("别名"),
                "type",
                "PERSON",
                "biography",
                "人工维护的歌手资料",
                "active",
                active,
                "revision",
                revision);
    }

    JsonNode create(String name) throws Exception {
        var r =
                req(
                        admin,
                        "/api/v1/artists",
                        "POST",
                        input(UUID.randomUUID().toString(), name, 0, true),
                        null,
                        true);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(201);
        return json.readTree(r.body());
    }

    Map<String, Object> credit(String song, String name, long revision) {
        return Map.of("songId", song, "credit", name, "revision", revision);
    }

    JsonNode bind(String artist, List<Map<String, Object>> items) throws Exception {
        return ok(
                admin,
                "/api/v1/artists/bindings",
                "POST",
                new com.mynora.singverse.media.artist.ArtistDtos.Bind(
                        artist,
                        items.stream()
                                .map(
                                        i ->
                                                new com.mynora.singverse.media.artist.ArtistDtos
                                                        .Credit(
                                                        i.get("songId").toString(),
                                                        i.get("credit").toString(),
                                                        ((Number) i.get("revision")).longValue()))
                                .toList()),
                null,
                true);
    }

    @Test
    void scanningCreatesArtistsAndManualCorrectionsSurviveRescan() throws Exception {
        String token = device();
        String a = song("歌曲甲", List.of("歌手甲", " 歌手甲 ", "歌手乙"));
        var first = ok(admin, "/api/v1/artists?q=歌手甲", "GET", null, null, false);
        assertThat(first.path("totalElements").asInt()).isEqualTo(1);
        String old = first.path("items").get(0).path("id").asText();
        assertThat(first.path("items").get(0).path("count").asInt()).isEqualTo(1);
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/artists",
                                        "POST",
                                        input(UUID.randomUUID().toString(), "人工建档", 0, true),
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(410);
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/artists/bindings",
                                        "POST",
                                        Map.of(
                                                "artistId",
                                                old,
                                                "items",
                                                List.of(credit(a, "歌手甲", 0))),
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(410);
        var review = ok(admin, "/api/v1/metadata/songs/" + a + "/review", "GET", null, null, false);
        var edits =
                Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "fingerprint",
                        review.path("fingerprint").asText(),
                        "version",
                        review.path("version").asText(),
                        "fields",
                        List.of("artists"),
                        "edits",
                        Map.of("artists", List.of("订正歌手")));
        ok(admin, "/api/v1/metadata/songs/" + a + "/review/apply", "POST", edits, null, true);
        assertThat(
                        ok(admin, "/api/v1/artists?q=歌手甲", "GET", null, null, false)
                                .path("totalElements")
                                .asInt())
                .isZero();
        var next =
                ok(admin, "/api/v1/artists?q=订正歌手", "GET", null, null, false).path("items").get(0);
        String id = next.path("id").asText();
        assertThat(ok(admin, "/api/v1/artists?type=UNKNOWN&countLessThan=2", "GET", null, null, false).path("totalElements").asInt()).isGreaterThan(0);
        ok(admin, "/api/v1/artists/" + id + "/category", "PUT", Map.of("type", "FEMALE", "revision", next.path("revision").asLong()), null, true);
        assertThat(ok(admin, "/api/v1/artists?q=订正歌手&type=FEMALE&countLessThan=2", "GET", null, null, false).path("totalElements").asInt()).isEqualTo(1);
        assertThat(ok(admin, "/api/v1/artists?q=订正歌手&type=GROUP", "GET", null, null, false).path("totalElements").asInt()).isZero();
        assertThat(ok(admin,"/api/v1/artists/"+old,"GET",null,null,false).path("count").asInt()).isZero();
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/artists/" + id + "/songs",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("totalElements")
                                .asInt())
                .isEqualTo(1);
        var before = songRepository.get(a);
        fixture.update("UPDATE media_song SET file_size=11,modified_at='changed' WHERE id=?", a);
        library.invalidate();
        assertThat(songRepository.get(a).metadata().track().artists()).containsExactly("订正歌手");
        var state = ok(admin, "/api/v1/artists/song-overrides/" + a, "GET", null, null, false);
        assertThat(state.path("overridden").asBoolean()).isTrue();
        assertThat(
                        req(
                                        admin,
                                        "/api/v1/artists/song-overrides/" + a + "?revision=99",
                                        "DELETE",
                                        null,
                                        null,
                                        true)
                                .statusCode())
                .isEqualTo(409);
        ok(
                admin,
                "/api/v1/artists/song-overrides/"
                        + a
                        + "?revision="
                        + state.path("revision").asLong(),
                "DELETE",
                null,
                null,
                true);
        assertThat(
                        ok(admin, "/api/v1/artists?q=歌手甲", "GET", null, null, false)
                                .path("items")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(old);
        assertThat(req(guest, "/api/v1/artists", "GET", null, token, false).statusCode())
                .isEqualTo(401);
    }

    byte[] png(int color) throws Exception {
        var image =
                new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(color));
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    HttpResponse<String> upload(String id, String kind, String version, byte[] bytes, boolean csrf)
            throws Exception {
        String boundary = "artist-" + UUID.randomUUID();
        var out = new java.io.ByteArrayOutputStream();
        out.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"
                                + version
                                + "\r\n--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"../image.png\"\r\n"
                                + "Content-Type: image/png\r\n\r\n")
                        .getBytes());
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes());
        var req =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/artists/"
                                                + id
                                                + "/images/"
                                                + kind))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (csrf)
            req.header(
                    "X-CSRF-Token",
                    json.readTree(req(admin, "/api/v1/auth/csrf", "GET", null, null, false).body())
                            .path("token")
                            .asText());
        return admin.send(
                req.PUT(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void manualArtworkHasIndependentVersionsAndRemovalBlocksAutomaticFetch() throws Exception {
        String token = device();
        song("图片歌曲", List.of("图片歌手"));
        String id =
                ok(admin, "/api/v1/artists?q=图片歌手", "GET", null, null, false)
                        .path("items")
                        .get(0)
                        .path("id")
                        .asText();
        byte[] portrait = png(0x305080), background = png(0x906040);
        assertThat(upload(id, "portrait", "", portrait, false).statusCode()).isEqualTo(403);
        assertThat(upload(id, "avatar", "", portrait, true).statusCode()).isEqualTo(400);
        assertThat(upload(id, "portrait", "", "invalid".getBytes(), true).statusCode())
                .isEqualTo(400);
        var r = upload(id, "portrait", "", portrait, true);
        assertThat(r.statusCode()).isEqualTo(200);
        String version = json.readTree(r.body()).path("portraitVersion").asText();
        assertThat(upload(id, "background", "", background, true).statusCode()).isEqualTo(200);
        assertThat(artists.policy(id, "PORTRAIT")).isEqualTo(1);
        assertThat(upload(id, "portrait", "wrong", background, true).statusCode()).isEqualTo(409);
        assertThat(
                        req(
                                        guest,
                                        "/api/v1/tv/library/artists/" + id + "/images/portrait",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .statusCode())
                .isEqualTo(200);
        ok(
                admin,
                "/api/v1/artists/" + id + "/images/portrait?version=" + version,
                "DELETE",
                null,
                null,
                true);
        assertThat(artists.policy(id, "PORTRAIT")).isEqualTo(1);
        ok(admin, "/api/v1/artists/" + id + "/images/retry", "POST", Map.of(), null, true);
        assertThat(artists.policy(id, "PORTRAIT")).isZero();
        assertThat(artists.find(id).get("fetch_state")).isEqualTo("PENDING");
        assertThat(
                        req(
                                        guest,
                                        "/api/v1/artists/" + id + "/images/retry",
                                        "POST",
                                        Map.of(),
                                        token,
                                        false)
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void embeddedArtistCanBeCorrectedAndRescanPreservesOverrideUntilExplicitReset()
            throws Exception {
        device();
        String id = song("内嵌曲", List.of("文件名歌手"));
        var tags =
                new com.mynora.singverse.infrastructure.compute.MediaTagsClient.Tags(
                        "内嵌曲", List.of("错误内嵌歌手"), null, null);
        fixture.update(
                "UPDATE media_song SET embedded_tags_json=? WHERE id=?",
                json.writeValueAsString(tags),
                id);
        library.invalidate();
        assertThat(songRepository.get(id).metadata().origins().get("artists").kind())
                .isEqualTo("FILE");
        var review =
                ok(admin, "/api/v1/metadata/songs/" + id + "/review", "GET", null, null, false);
        ok(
                admin,
                "/api/v1/metadata/songs/" + id + "/review/apply",
                "POST",
                Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "fingerprint",
                        review.path("fingerprint").asText(),
                        "version",
                        review.path("version").asText(),
                        "fields",
                        List.of("artists"),
                        "edits",
                        Map.of("artists", List.of("正确歌手"))),
                null,
                true);
        var row = fixture.queryForMap("SELECT * FROM media_song WHERE id=?", id);
        String source = row.get("source_id").toString();
        songRepository.replace(
                source,
                List.of(
                        new com.mynora.singverse.media.repository.MediaSongRepository.Stored(
                                id,
                                source,
                                row.get("relative_path").toString(),
                                row.get("path_hash").toString(),
                                11L,
                                "2026-10-30T00:00:00Z",
                                "2026-10-30T00:00:00Z",
                                row.get("parser_profile_id").toString(),
                                ((Number) row.get("parser_revision")).longValue(),
                                null,
                                row.get("result_json").toString(),
                                false,
                                json.writeValueAsString(tags))));
        assertThat(songRepository.get(id).metadata().track().artists()).containsExactly("正确歌手");
        assertThat(
                        ok(admin, "/api/v1/artists?q=正确歌手", "GET", null, null, false)
                                .path("totalElements")
                                .asInt())
                .isEqualTo(1);
        var state = ok(admin, "/api/v1/artists/song-overrides/" + id, "GET", null, null, false);
        ok(
                admin,
                "/api/v1/artists/song-overrides/"
                        + id
                        + "?revision="
                        + state.path("revision").asLong(),
                "DELETE",
                null,
                null,
                true);
        assertThat(songRepository.get(id).metadata().track().artists()).containsExactly("错误内嵌歌手");
        assertThat(songRepository.get(id).metadata().origins().get("artists").kind())
                .isEqualTo("FILE");
        assertThat(
                        ok(admin, "/api/v1/artists?q=正确歌手", "GET", null, null, false)
                                .path("totalElements")
                                .asInt())
                .isZero();
    }

    @Autowired com.mynora.singverse.media.artist.ArtistService artistService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.context.ApplicationEventPublisher events;

    @Test
    void fanartKeyIsEncryptedAndOnlyConfigurationStateIsReturned() throws Exception {
        ok(admin, "/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), null, true);
        var saved = ok(admin, "/api/v1/artists/artwork-settings", "PUT", Map.of("fanartKey", "sample-personal-key", "clearFanartKey", false), null, true);
        assertThat(saved.path("fanartConfigured").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain("sample-personal-key");
        assertThat(artists.fanartKey()).doesNotContain("sample-personal-key");
        assertThat(ok(admin, "/api/v1/artists/artwork-settings", "GET", null, null, false).path("fanartConfigured").asBoolean()).isTrue();
        assertThat(ok(admin, "/api/v1/artists/artwork-settings", "PUT", Map.of("clearFanartKey", true), null, true).path("fanartConfigured").asBoolean()).isFalse();
    }

    @Test
    void existingPortraitDoesNotBlockBackgroundFetch() throws Exception {
        device();
        fixture.update("UPDATE artist_image_fetch SET state='NO_MATCH'");
        song("背景测试曲", List.of("背景测试歌手"));
        var result = library.managedArtists("背景测试歌手",0,12);
        String id = ((JsonNode)((List<?>)result.get("items")).getFirst()).path("id").asText();
        byte[] image = png(0x427688);
        artistService.upload(id,"portrait","",new org.springframework.mock.web.MockMultipartFile("file","portrait.png","image/png",image));
        var supplements = org.mockito.Mockito.mock(com.mynora.singverse.media.artist.ArtistArtworkSources.class);
        org.mockito.Mockito.when(supplements.find(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new com.mynora.singverse.media.artist.ArtistArtworkSources.Choice("THEAUDIODB","123","d84d02ad-d74c-4227-8073-6f13a039cfac","","https://r2.theaudiodb.com/background.jpg","TheAudioDB")));
        var worker = new com.mynora.singverse.media.artist.ArtistArtworkWorker(artists,artistService,library,
                org.mockito.Mockito.mock(com.mynora.singverse.media.metadata.ArtistArtworkProvider.class),supplements,json,transactions,events,false) {
            @Override protected byte[] read(String url) { return image; }
        };
        try {
            worker.tick();
            worker.tick();
            assertThat(artists.image(id,"PORTRAIT").get("origin")).isEqualTo("UPLOAD");
            assertThat(artists.image(id,"BACKGROUND").get("origin")).isEqualTo("AUTO");
            assertThat(artists.imageFetch(id,"BACKGROUND").get("state")).isEqualTo("READY");
        } finally { worker.destroy(); }
    }

    @Test
    void unverifiedArtworkBecomesReviewCandidate() throws Exception {
        device();
        fixture.update("UPDATE artist_image_fetch SET state='NO_MATCH'");
        song("候选歌曲",List.of("候选歌手"));
        var result=library.managedArtists("候选歌手",0,12);
        String id=((JsonNode)((List<?>)result.get("items")).getFirst()).path("id").asText();
        var supplements=org.mockito.Mockito.mock(com.mynora.singverse.media.artist.ArtistArtworkSources.class);
        org.mockito.Mockito.when(supplements.find(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(new com.mynora.singverse.media.artist.ArtistArtworkSources.Choice("THEAUDIODB","123","","https://r2.theaudiodb.com/portrait.jpg","","TheAudioDB",false)));
        var worker=new com.mynora.singverse.media.artist.ArtistArtworkWorker(artists,artistService,library,
                org.mockito.Mockito.mock(com.mynora.singverse.media.metadata.ArtistArtworkProvider.class),supplements,json,transactions,events,false);
        try {
            worker.tick();
            assertThat(artists.image(id,"PORTRAIT")).isNull();
            assertThat(artists.imageFetch(id,"PORTRAIT").get("state")).isEqualTo("NEEDS_REVIEW");
            assertThat(artistService.candidates(id,"portrait")).hasSize(1);
        } finally { worker.destroy(); }
    }

    com.mynora.singverse.media.artist.ArtistArtworkWorker worker(
            com.mynora.singverse.media.metadata.ArtistArtworkProvider provider, byte[] image) {
        var artworkSources = org.mockito.Mockito.mock(com.mynora.singverse.media.artist.ArtistArtworkSources.class);
        org.mockito.Mockito.when(artworkSources.find(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        return new com.mynora.singverse.media.artist.ArtistArtworkWorker(
                artists, artistService, library, provider, artworkSources, json, transactions, events, false) {
            @Override
            protected byte[] read(String url) {
                return image;
            }
        };
    }

    @Test
    void automaticArtworkNeverOverwritesConcurrentUploadAndRetriesAreBounded() throws Exception {
        device();
        fixture.update("UPDATE artist_image_fetch SET state='NO_MATCH'");
        song("取图歌曲", List.of("取图歌手"));
        var profile = library.managedArtists("取图歌手", 0, 12);
        String id =
                ((com.fasterxml.jackson.databind.JsonNode)
                                ((List<?>) profile.get("items")).getFirst())
                        .path("id")
                        .asText();
        var provider =
                org.mockito.Mockito.mock(
                        com.mynora.singverse.media.metadata.ArtistArtworkProvider.class);
        byte[] image = png(0x506070);
        org.mockito.Mockito.when(
                        provider.find(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyList(),
                                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(
                        call -> {
                            var file =
                                    new org.springframework.mock.web.MockMultipartFile(
                                            "file", "portrait.png", "image/png", image);
                            artistService.upload(id, "portrait", "", file);
                            return new com.mynora.singverse.media.metadata.ArtistArtworkProvider
                                    .Artwork(
                                    "NETEASE", "123", "https://p1.music.126.net/photo.jpg", null);
                        });
        var worker = worker(provider, image);
        try {
            worker.tick();
            assertThat(artists.image(id, "PORTRAIT").get("origin")).isEqualTo("UPLOAD");
            assertThat(artists.imageFetch(id,"PORTRAIT").get("state")).isEqualTo("READY");
            fixture.update("UPDATE artist_image_fetch SET state='READY' WHERE artist_id=? AND image_kind='BACKGROUND'", id);
            String version = artists.image(id, "PORTRAIT").get("version").toString();
            artistService.removeImage(id, "portrait", version);
            artistService.retry(id);
            org.mockito.Mockito.reset(provider);
            org.mockito.Mockito.when(
                            provider.find(
                                    org.mockito.ArgumentMatchers.anyString(),
                                    org.mockito.ArgumentMatchers.anyList(),
                                    org.mockito.ArgumentMatchers.anyList()))
                    .thenThrow(new IllegalStateException("offline"));
            for (int i = 0; i < 4; i++) {
                fixture.update("UPDATE artist_image_fetch SET fetch_after='' WHERE artist_id=? AND image_kind='PORTRAIT'", id);
                worker.tick();
            }
            assertThat(artists.imageFetch(id,"PORTRAIT").get("state")).isEqualTo("FAILED");
            assertThat(((Number) artists.imageFetch(id,"PORTRAIT").get("attempts")).intValue()).isEqualTo(4);
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(4))
                    .find(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyList(),
                            org.mockito.ArgumentMatchers.anyList());
            artistService.retry(id);
            org.mockito.Mockito.reset(provider);
            worker.tick();
            assertThat(artists.imageFetch(id,"PORTRAIT").get("state")).isEqualTo("NO_MATCH");
            worker.tick();
            org.mockito.Mockito.verify(provider, org.mockito.Mockito.times(1))
                    .find(
                            org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyList(),
                            org.mockito.ArgumentMatchers.anyList());
        } finally {
            worker.destroy();
        }
    }
}
