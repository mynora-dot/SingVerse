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
class TvHttpTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path", () -> temp.resolve("tv.db").toString());
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

    @Test
    void deviceCannotBecomeAdministratorAndRevocationInvalidatesCredential() throws Exception {
        String token = device();
        assertThat(req(guest, "/api/v1/media-songs", "GET", null, token, false).statusCode())
                .isEqualTo(401);
        assertThat(req(guest, "/api/v1/tv/library/songs", "GET", null, null, false).statusCode())
                .isEqualTo(401);
        var list = ok(guest, "/api/v1/tv/library/songs", "GET", null, token, false);
        assertThat(list.path("items").isArray()).isTrue();
        var devices = ok(admin, "/api/v1/admin/tv-devices", "GET", null, null, false);
        assertThat(devices.path("devices").size())
                .isPositive(); // Find the actual device using its credential.
        var credential = db.secret(com.mynora.singverse.auth.crypto.AuthCrypto.hash(token));
        String actual =
                json.readTree(credential.get("document").toString()).path("deviceId").asText();
        assertThat(
                        req(admin, "/api/v1/admin/tv-devices/" + actual, "DELETE", null, null, true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(req(guest, "/api/v1/tv/library/songs", "GET", null, token, false).statusCode())
                .isEqualTo(401);
    }

    HttpResponse<String> uploadTagCover(String id,String version,String mime,byte[] bytes,boolean csrf,String token) throws Exception {
        String boundary="sv-"+UUID.randomUUID();
        var output=new java.io.ByteArrayOutputStream();
        output.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../cover.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(bytes);output.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/classification/tags/"+id+"/cover")).header("Content-Type","multipart/form-data; boundary="+boundary);
        if(csrf)request.header("X-CSRF-Token",json.readTree(req(admin,"/api/v1/auth/csrf","GET",null,null,false).body()).path("token").asText());
        if(token!=null)request.header("Authorization","Bearer "+token);
        return admin.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    byte[] png(int color,int width) throws Exception {
        var image=new java.awt.image.BufferedImage(width,8,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(new java.awt.Color(color));graphics.fillRect(0,0,width,8);graphics.dispose();
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    @Test void classificationCoversAreVersionedAndProtected() throws Exception {
        String token=device();
        var created=req(admin,"/api/v1/classification/tags","POST",Map.of("dimension","CUSTOM","name","封面测试分类","aliases",List.of(),"active",true,"revision",0),null,true);assertThat(created.statusCode()).isEqualTo(201);var tag=json.readTree(created.body());
        String id=tag.path("id").asText();byte[] image=png(0x426080,32);
        assertThat(uploadTagCover(id,"","image/png",image,false,null).statusCode()).isEqualTo(403);
        assertThat(uploadTagCover(id,"","image/png","invalid-image".getBytes(),true,null).statusCode()).isEqualTo(400);
        assertThat(uploadTagCover(id,"","image/jpeg",image,true,null).statusCode()).isEqualTo(400);
        assertThat(uploadTagCover(id,"","image/png",png(0,4097),true,null).statusCode()).isEqualTo(400);
        assertThat(uploadTagCover(id,"","image/png",new byte[2_000_001],true,null).statusCode()).isEqualTo(400);
        assertThat(uploadTagCover("missing","","image/png",image,true,null).statusCode()).isEqualTo(404);
        var response=uploadTagCover(id,"","image/png",image,true,null);assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        var saved=json.readTree(response.body());String version=saved.path("coverVersion").asText();assertThat(version).hasSize(64);assertThat(saved.path("revision")).isEqualTo(tag.path("revision"));
        assertThat(json.readTree(uploadTagCover(id,"","image/png",image,true,null).body()).path("coverVersion").asText()).isEqualTo(version);
        String path="/api/v1/tv/library/classification-tags/"+id+"/cover";
        assertThat(req(guest,path,"GET",null,null,false).statusCode()).isEqualTo(401);
        var binary=guest.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(binary.statusCode()).isEqualTo(200);assertThat(binary.headers().firstValue("Content-Type").orElse("")).contains("image/jpeg");assertThat(binary.body()[0]).isEqualTo((byte)0xff);
        assertThat(uploadTagCover(id,"wrong-version","image/png",png(0x824260,32),true,null).statusCode()).isEqualTo(409);
        var changed=json.readTree(uploadTagCover(id,version,"image/png",png(0x824260,32),true,null).body());String next=changed.path("coverVersion").asText();assertThat(next).isNotEqualTo(version);
        var list=ok(guest,"/api/v1/tv/library/classification-tags","GET",null,token,false);
        assertThat(java.util.stream.StreamSupport.stream(list.spliterator(),false).filter(t->id.equals(t.path("id").asText())).toList()).singleElement().satisfies(t->{assertThat(t.path("coverVersion").asText()).isEqualTo(next);assertThat(t.path("coverPath").asText()).isEqualTo(path);});
        ok(admin,"/api/v1/classification/tags/"+id,"PUT",Map.of("dimension","CUSTOM","name","封面测试分类","aliases",List.of(),"active",false,"revision",tag.path("revision").asLong()),null,true);
        assertThat(req(guest,path,"GET",null,token,false).statusCode()).isEqualTo(404);
        assertThat(req(admin,"/api/v1/classification/tags/"+id+"/cover","GET",null,null,false).statusCode()).isEqualTo(200);
        assertThat(req(admin,"/api/v1/classification/tags/"+id+"/cover?version="+version,"DELETE",null,null,true).statusCode()).isEqualTo(409);
        var removed=ok(admin,"/api/v1/classification/tags/"+id+"/cover?version="+next,"DELETE",null,null,true);assertThat(removed.path("coverPath").isNull()).isTrue();
        assertThat(req(admin,"/api/v1/classification/tags/"+id+"/cover","GET",null,null,false).statusCode()).isEqualTo(404);
    }

    @Autowired com.mynora.singverse.media.repository.MediaSongRepository songRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate fixture;

    void seed() throws Exception {
        Files.writeString(temp.resolve("sample.mp4"), "0123456789");
        fixture.update(
                "INSERT INTO"
                    + " media_source(id,name,source_type,location,location_hash,username,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                "tv-source",
                "电视测试曲库",
                "LOCAL",
                temp.toString(),
                "unique-tv-source",
                "",
                "2026-10-29T00:00:00Z",
                "2026-10-29T00:00:00Z");
        var parsed =
                new com.mynora.singverse.media.filename.FilenameParseResult(
                        "sample.mp4",
                        "sample.mp4",
                        "测试歌曲",
                        List.of("测试歌手"),
                        com.mynora.singverse.media.filename.FilenameParseResult.Language.MANDARIN,
                        com.mynora.singverse.media.filename.FilenameParseResult.VersionType.UNKNOWN,
                        "00842",
                        "mp4",
                        List.of(),
                        1.0,
                        com.mynora.singverse.media.filename.FilenameParseResult.ParseStatus.SUCCESS,
                        com.mynora.singverse.media.filename.FilenameParseResult.RuleId.TITLE_ONLY,
                        List.of(),
                        false,
                        List.of());
        songRepository.replace(
                "tv-source",
                List.of(
                        new com.mynora.singverse.media.repository.MediaSongRepository.Stored(
                                "tv-song",
                                "tv-source",
                                "sample.mp4",
                                "tv-path-hash",
                                10L,
                                "2026-10-29T00:00:00Z",
                                "2026-10-29T00:00:00Z",
                                "default",
                                0,
                                null,
                                songRepository.encode(parsed),
                                false)));
    }

    @Test
    void originalRangeQueuePlaybackAndStaleEvents() throws Exception {
        seed();
        String token = device();
        var listed = ok(guest, "/api/v1/tv/library/songs", "GET", null, token, false);
        assertThat(listed.toString()).doesNotContain("relativePath", "password", "location");
        assertThat(req(guest, "/api/v1/tv/home", "GET", null, null, false).statusCode())
                .isEqualTo(401);
        var home = ok(guest, "/api/v1/tv/home", "GET", null, token, false);
        assertThat(home.path("totalSongs").asLong()).isEqualTo(1);
        assertThat(home.path("recentSongs").get(0).path("id").asText()).isEqualTo("tv-song");
        assertThat(home.path("server").path("instanceId").asText()).isNotBlank();
        var detail = ok(guest, "/api/v1/tv/library/songs/tv-song", "GET", null, token, false);
        assertThat(detail.path("title").asText()).isEqualTo("测试歌曲");
        assertThat(detail.path("revision").asText()).isNotBlank();
        assertThat(
                        ok(guest, "/api/v1/tv/library/songs?q=00842", "GET", null, token, false)
                                .path("totalElements")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        ok(guest, "/api/v1/tv/library/songs?q=842", "GET", null, token, false)
                                .path("totalElements")
                                .asInt())
                .isZero();
        ok(
                admin,
                "/api/v1/classification/songs/tv-song",
                "PUT",
                Map.of(
                        "requestId",
                        UUID.randomUUID().toString(),
                        "revision",
                        0,
                        "dimensions",
                        Map.of("GENRE", List.of("genre-0")),
                        "lockedDimensions",
                        List.of()),
                null,
                true);
        ok(guest, "/api/v1/tv/home", "GET", null, token, false);
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/songs?q=ceshi&tagIds=genre-0&tagIds=genre-1",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("totalElements")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/songs?tagIds=genre-0&tagIds=mood-0",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("totalElements")
                                .asInt())
                .isZero();
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/songs?unclassified=true",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("totalElements")
                                .asInt())
                .isZero();

        assertThat(
                        ok(guest, "/api/v1/tv/library/songs?q=ceshigequ", "GET", null, token, false)
                                .path("totalElements")
                                .asInt())
                .isEqualTo(1);
        assertThat(
                        ok(
                                        guest,
                                        "/api/v1/tv/library/songs?q=csgq&page=1&size=1",
                                        "GET",
                                        null,
                                        token,
                                        false)
                                .path("items")
                                .isEmpty())
                .isTrue();
        assertThat(
                        ok(guest, "/api/v1/tv/library/artists?q=csgs", "GET", null, token, false)
                                .path("items")
                                .size())
                .isEqualTo(1);
        Files.writeString(temp.resolve("sample.lrc"), "[00:01.00]真实歌词");
        var text =
                ok(
                        guest,
                        "/api/v1/tv/library/songs/tv-song/text-tracks",
                        "GET",
                        null,
                        token,
                        false);
        assertThat(text.path("items").get(0).path("text").asText()).contains("真实歌词");
        Path outside = Files.createTempFile("singverse-outside-text-", ".vtt");
        try {
            Files.writeString(outside, "outside-secret");
            Files.createSymbolicLink(temp.resolve("sample.vtt"), outside);
            assertThat(
                            ok(
                                            guest,
                                            "/api/v1/tv/library/songs/tv-song/text-tracks",
                                            "GET",
                                            null,
                                            token,
                                            false)
                                    .toString())
                    .doesNotContain("outside-secret");
        } finally {
            Files.deleteIfExists(temp.resolve("sample.vtt"));
            Files.deleteIfExists(outside);
        }
        Files.writeString(temp.resolve("sample.srt"), "x".repeat(131073));
        text = ok(guest, "/api/v1/tv/library/songs/tv-song/text-tracks", "GET", null, token, false);
        assertThat(text.path("items").size()).isEqualTo(1);
        assertThat(text.path("warnings").size()).isEqualTo(1);
        assertThat(req(guest, "/api/v1/tv/releases/current", "GET", null, null, false).statusCode())
                .isEqualTo(401);
        assertThat(
                        ok(guest, "/api/v1/tv/releases/current", "GET", null, token, false)
                                .path("available")
                                .asBoolean())
                .isFalse();
        assertThat(detail.toString())
                .doesNotContain("relativePath", "password", "location", "embeddedTagsJson");
        assertThat(
                        req(guest, "/api/v1/tv/library/songs/missing", "GET", null, token, false)
                                .statusCode())
                .isEqualTo(404);
        // A search yielding no results must not replace the independent home rail.
        var empty =
                ok(guest, "/api/v1/tv/library/songs?q=missing-keyword", "GET", null, token, false);
        assertThat(empty.path("totalElements").asLong()).isZero();
        assertThat(
                        ok(guest, "/api/v1/tv/home", "GET", null, token, false)
                                .path("totalSongs")
                                .asLong())
                .isEqualTo(1);
        // Simulate newly saved file metadata; both home and detail must use the effective values.
        fixture.update(
                "UPDATE media_song SET embedded_tags_json=? WHERE id=?",
                "{\"title\":\"最新文件歌名\",\"artists\":[\"测试歌手\"],\"album\":\"实际专辑\",\"releaseDate\":\"2024-01-02\"}",
                "tv-song");
        var updated = ok(guest, "/api/v1/tv/library/songs/tv-song", "GET", null, token, false);
        assertThat(updated.path("title").asText()).isEqualTo("最新文件歌名");
        assertThat(updated.path("album").asText()).isEqualTo("实际专辑");
        assertThat(updated.path("revision")).isNotEqualTo(detail.path("revision"));
        assertThat(
                        ok(guest, "/api/v1/tv/home", "GET", null, token, false)
                                .path("recentSongs")
                                .get(0)
                                .path("title")
                                .asText())
                .isEqualTo("最新文件歌名");

        var resource =
                ok(
                        guest,
                        "/api/v1/tv/playback-resources",
                        "POST",
                        Map.of("songId", "tv-song"),
                        token,
                        false);
        String url = resource.path("url").asText();
        var range =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + url))
                        .header("Authorization", "Bearer " + token)
                        .header("Range", "bytes=2-5")
                        .build();
        var media = guest.send(range, HttpResponse.BodyHandlers.ofString());
        assertThat(media.statusCode()).isEqualTo(206);
        assertThat(media.body()).isEqualTo("2345");
        var session = ok(guest, "/api/v1/tv/sessions", "POST", null, token, false);
        String base = "/api/v1/tv/sessions/" + session.path("id").asText();
        var added =
                ok(
                        guest,
                        base + "/commands",
                        "POST",
                        Map.of(
                                "action",
                                "ADD",
                                "requestId",
                                "add-1",
                                "songId",
                                "tv-song",
                                "expectedRevision",
                                session.path("revision").asLong()),
                        token,
                        false);
        var start =
                ok(
                        guest,
                        base + "/commands",
                        "POST",
                        Map.of(
                                "action",
                                "START",
                                "requestId",
                                "start-1",
                                "expectedRevision",
                                added.path("revision").asLong()),
                        token,
                        false);
        String attempt = start.path("playback").path("attemptId").asText();
        assertThat(start.path("queue").get(0).path("status").asText()).isEqualTo("HANDOFF");
        var playing =
                ok(
                        guest,
                        base + "/playback-events",
                        "POST",
                        Map.of(
                                "attemptId",
                                attempt,
                                "eventSeq",
                                1,
                                "state",
                                "PLAYING",
                                "positionMs",
                                100),
                        token,
                        false);
        assertThat(playing.path("queue").get(0).path("status").asText()).isEqualTo("PLAYING");
        var stale =
                ok(
                        guest,
                        base + "/playback-events",
                        "POST",
                        Map.of(
                                "attemptId",
                                attempt,
                                "eventSeq",
                                0,
                                "state",
                                "ENDED",
                                "positionMs",
                                0),
                        token,
                        false);
        assertThat(stale.path("playback").path("positionMs").asLong()).isEqualTo(100);
        assertThat(
                        req(
                                        guest,
                                        base + "/playback-events",
                                        "POST",
                                        Map.of(
                                                "attemptId",
                                                "old-attempt",
                                                "eventSeq",
                                                99,
                                                "state",
                                                "ENDED"),
                                        token,
                                        false)
                                .statusCode())
                .isEqualTo(409);
        var artists = ok(guest, "/api/v1/tv/library/artists", "GET", null, token, false);
        assertThat(artists.path("items").get(0).path("name").asText()).isEqualTo("测试歌手");
        var second =
                ok(
                        guest,
                        base + "/commands",
                        "POST",
                        Map.of(
                                "action",
                                "ADD",
                                "songId",
                                "tv-song",
                                "requestId",
                                "add-2",
                                "expectedRevision",
                                playing.path("revision").asLong()),
                        token,
                        false);
        var next =
                ok(
                        guest,
                        base + "/commands",
                        "POST",
                        Map.of(
                                "action",
                                "NEXT",
                                "requestId",
                                "next-1",
                                "expectedRevision",
                                second.path("revision").asLong()),
                        token,
                        false);
        assertThat(next.path("playback").path("confirmed").asBoolean()).isFalse();
        assertThat(next.path("commands").size()).isEqualTo(1);
        assertThat(next.path("commands").get(0).path("action").asText()).isEqualTo("STOP");
        assertThat(
                        req(
                                        guest,
                                        base + "/commands",
                                        "POST",
                                        Map.of(
                                                "action",
                                                "RESUME",
                                                "requestId",
                                                "resume-too-early",
                                                "expectedRevision",
                                                next.path("revision").asLong()),
                                        token,
                                        false)
                                .statusCode())
                .isEqualTo(409);
        var recovered = ok(guest, base + "/recover", "POST", null, token, false);
        assertThat(recovered.has("playback")).isFalse();
        assertThat(recovered.path("commands").size()).isZero();
        assertThat(recovered.path("queue").get(1).path("status").asText()).isEqualTo("WAITING");
    }

    @Test
    void invitationJoinHostBoundaryAndIdempotentLockAndEnd() throws Exception {
        String token = device();
        var session = ok(guest, "/api/v1/tv/sessions", "POST", null, token, false);
        String sid = session.path("id").asText(), base = "/api/v1/tv/sessions/" + sid;
        var invitation = ok(guest, base + "/invitations", "POST", null, token, false);
        var data = Map.of("invitation", invitation.path("invitation").asText());
        var before = db.list("participant").size();
        ok(guest, "/api/v1/party/invitations/preview", "POST", data, null, false);
        assertThat(db.list("participant")).hasSize(before);
        var joined =
                ok(
                        guest,
                        "/api/v1/party/join",
                        "POST",
                        Map.of("invitation", data.get("invitation"), "name", "亲友"),
                        null,
                        false);
        String party = joined.path("token").asText();
        for (String deviceOnly :
                List.of(
                        "/api/v1/tv/releases/current",
                        "/api/v1/tv/releases/apk",
                        "/api/v1/tv/library/songs/missing/text-tracks"))
            assertThat(req(guest, deviceOnly, "GET", null, party, false).statusCode())
                    .isEqualTo(403);
        assertThat(req(guest, base + "/invitations", "POST", null, party, false).statusCode())
                .isEqualTo(403);
        var snap = ok(guest, base + "/snapshot", "GET", null, token, false);
        var change =
                Map.of(
                        "action",
                        "LOCK",
                        "requestId",
                        "lock-once",
                        "expectedRevision",
                        snap.path("revision").asLong());
        var locked = ok(guest, base + "/commands", "POST", change, token, false);
        assertThat(locked.path("acceptingRequests").asBoolean()).isFalse();
        assertThat(locked.path("stateRevision").asLong())
                .isGreaterThan(snap.path("stateRevision").asLong());
        assertThat(ok(guest, base + "/commands", "POST", change, token, false).path("revision"))
                .isEqualTo(locked.path("revision"));
        assertThat(
                        req(
                                        guest,
                                        base + "/commands",
                                        "POST",
                                        Map.of(
                                                "action",
                                                "ADD",
                                                "requestId",
                                                "rejected-add",
                                                "songId",
                                                "missing",
                                                "expectedRevision",
                                                locked.path("revision").asLong()),
                                        party,
                                        false)
                                .statusCode())
                .isEqualTo(409);
        assertThat(
                        req(
                                        guest,
                                        base + "/commands",
                                        "POST",
                                        Map.of(
                                                "action",
                                                "UNLOCK",
                                                "requestId",
                                                "guest-lock",
                                                "expectedRevision",
                                                locked.path("revision").asLong()),
                                        party,
                                        false)
                                .statusCode())
                .isEqualTo(403);
        ok(
                guest,
                base + "/commands",
                "POST",
                Map.of(
                        "action",
                        "END",
                        "requestId",
                        "end-once",
                        "expectedRevision",
                        locked.path("revision").asLong()),
                token,
                false);
        assertThat(
                        req(
                                        guest,
                                        "/api/v1/party/sessions/" + sid + "/snapshot",
                                        "GET",
                                        null,
                                        party,
                                        false)
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        req(guest, "/api/v1/party/invitations/preview", "POST", data, null, false)
                                .statusCode())
                .isEqualTo(410);
    }
}
