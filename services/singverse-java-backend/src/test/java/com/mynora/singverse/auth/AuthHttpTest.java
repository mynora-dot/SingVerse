package com.mynora.singverse.auth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.auth.controller.AuthController;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.auth.exception.AuthFailure;
import com.mynora.singverse.auth.repository.AuthStore;
import com.mynora.singverse.auth.service.AuthService;

import dev.samstevens.totp.code.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("sqlite")
class AuthHttpTest {
    @TempDir static Path temp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry properties) {
        properties.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
        properties.add("singverse.sqlite.path", () -> temp.resolve("auth.db").toString());
    }

    @LocalServerPort int port;
    @Autowired AuthStore store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.mynora.singverse.media.repository.MediaSourceMapper mediaSources;
    @Autowired AuthService service;
    @Autowired AuthCrypto crypto;
    HttpClient client;
    ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update(
                "UPDATE auth_account SET"
                    + " password_hash=?,default_password=1,mfa_secret=NULL,pending_secret=NULL,pending_expires=0,last_totp_step=-1"
                    + " WHERE id=1",
                store.passwords().encode("admin"));
        service.invalidate();
        jdbc.update("DELETE FROM media_source");
        jdbc.update("DELETE FROM auth_throttle");
        jdbc.update("DELETE FROM auth_recovery");
        client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
    }

    HttpResponse<String> request(String path, String method, Object data, boolean csrf)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json");
        if (csrf)
            builder.header(
                    "X-CSRF-Token",
                    body(request("/api/v1/auth/csrf", "GET", null, false)).get("token").asText());
        builder.method(
                method,
                data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        if (data != null) builder.header("Content-Type", "application/json");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    HttpResponse<String> login() throws Exception {
        return request(
                "/api/v1/auth/login",
                "POST",
                Map.of("username", "admin", "password", "admin"),
                true);
    }

    @Test
    void csrfAndSessionAreEnforcedAndLogoutRevokes() throws Exception {
        assertThat(request("/api/v1/system/parameters", "GET", null, false).statusCode())
                .isEqualTo(401);
        assertThat(
                        request(
                                        "/api/v1/auth/login",
                                        "POST",
                                        Map.of("username", "admin", "password", "admin"),
                                        false)
                                .statusCode())
                .isEqualTo(403);
        var logged = login();
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(body(logged).get("state").asText()).isEqualTo("authenticated");
        assertThat(logged.headers().allValues("Set-Cookie"))
                .anyMatch(
                        s ->
                                s.startsWith("singverse_session=")
                                        && s.contains("HttpOnly")
                                        && s.contains("SameSite=Lax"));
        assertThat(
                        body(request("/api/v1/auth/session", "GET", null, false))
                                .get("defaultPassword")
                                .asBoolean())
                .isTrue();
        assertThat(request("/api/v1/missing", "GET", null, false).statusCode()).isEqualTo(404);
        assertThat(request("/api/v1/missing", "POST", Map.of(), true).statusCode()).isEqualTo(404);
        assertThat(request("/api/v1/auth/logout", "POST", null, true).statusCode()).isEqualTo(200);
        assertThat(request("/api/v1/auth/session", "GET", null, false).statusCode()).isEqualTo(401);
    }

    @Test
    void passwordChangeRevokesSessionsAndDoesNotResetOnInitialization() throws Exception {
        login();
        assertThat(
                        request(
                                        "/api/v1/auth/password",
                                        "POST",
                                        Map.of("password", "admin", "newPassword", "short"),
                                        true)
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        request(
                                        "/api/v1/auth/password",
                                        "POST",
                                        Map.of(
                                                "password",
                                                "admin",
                                                "newPassword",
                                                "A long new password 123"),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(request("/api/v1/auth/session", "GET", null, false).statusCode()).isEqualTo(401);
        assertThat(login().statusCode()).isEqualTo(401);
        assertThat(
                        request(
                                        "/api/v1/auth/login",
                                        "POST",
                                        Map.of(
                                                "username",
                                                "admin",
                                                "password",
                                                "A long new password 123"),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        body(request("/api/v1/auth/session", "GET", null, false))
                                .get("defaultPassword")
                                .asBoolean())
                .isFalse();
        assertThat(store.account().get("password_hash").toString())
                .startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    }

    @Test
    void totpEnrollmentChallengeReplayAndRecoveryLifecycle() throws Exception {
        login();
        var setup =
                body(
                        request(
                                "/api/v1/system/parameters/mfa/setup",
                                "POST",
                                Map.of("password", "admin"),
                                true));
        String secret = setup.get("secret").asText();
        assertThat(setup.get("qrDataUrl").asText()).startsWith("data:image/png;base64,");
        assertThat(store.account().get("mfa_secret")).isNull();
        String code =
                new DefaultCodeGenerator().generate(secret, System.currentTimeMillis() / 30000);
        var confirmed =
                request(
                        "/api/v1/system/parameters/mfa/confirm",
                        "POST",
                        Map.of("code", code),
                        true);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        var codes = body(confirmed).get("recoveryCodes");
        assertThat(codes.size()).isEqualTo(10);
        assertThat(store.account().get("mfa_secret").toString())
                .startsWith("v1:")
                .doesNotContain(secret);
        assertThat(body(login()).get("state").asText()).isEqualTo("mfaRequired");
        assertThat(request("/api/v1/system/parameters", "GET", null, false).statusCode())
                .isEqualTo(401);
        assertThat(
                        request("/api/v1/auth/mfa/verify", "POST", Map.of("code", code), true)
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        request(
                                        "/api/v1/auth/mfa/verify",
                                        "POST",
                                        Map.of("code", codes.get(0).asText()),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        body(request("/api/v1/auth/session", "GET", null, false))
                                .get("recoveryCodesRemaining")
                                .asInt())
                .isEqualTo(9);
        request("/api/v1/auth/logout", "POST", null, true);
        login();
        assertThat(
                        request(
                                        "/api/v1/auth/mfa/verify",
                                        "POST",
                                        Map.of("code", codes.get(0).asText()),
                                        true)
                                .statusCode())
                .isEqualTo(401);
        assertThat(
                        request(
                                        "/api/v1/auth/mfa/verify",
                                        "POST",
                                        Map.of("code", codes.get(1).asText()),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(
                                        "/api/v1/system/parameters/mfa/recovery-codes",
                                        "POST",
                                        Map.of("password", "admin", "code", codes.get(2).asText()),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(request("/api/v1/auth/session", "GET", null, false).statusCode()).isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM auth_recovery WHERE code_hash=?",
                                Integer.class,
                                AuthCrypto.hash(codes.get(3).asText())))
                .isZero();
    }

    @Test
    void expiredSetupAndChallengeAttemptLimitAndCooldown() throws Exception {
        login();
        request("/api/v1/system/parameters/mfa/setup", "POST", Map.of("password", "admin"), true);
        jdbc.update("UPDATE auth_account SET pending_expires=0 WHERE id=1");
        assertThat(
                        request(
                                        "/api/v1/system/parameters/mfa/confirm",
                                        "POST",
                                        Map.of("code", "123456"),
                                        true)
                                .statusCode())
                .isEqualTo(409);
        jdbc.update(
                "UPDATE auth_account SET mfa_secret=? WHERE id=1",
                crypto.encrypt("JBSWY3DPEHPK3PXP"));
        service.invalidate();
        jdbc.update("DELETE FROM media_source");
        login();
        for (int i = 0; i < 5; i++)
            assertThat(
                            request(
                                            "/api/v1/auth/mfa/verify",
                                            "POST",
                                            Map.of("code", "invalid"),
                                            true)
                                    .statusCode())
                    .isEqualTo(401);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM auth_token WHERE kind='challenge'",
                                Integer.class))
                .isZero();
        var limited = login();
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void expiryOriginAndEncryptionFailuresAreClosed() throws Exception {
        login();
        jdbc.update("UPDATE auth_token SET last_seen=0 WHERE kind='session'");
        assertThat(request("/api/v1/auth/session", "GET", null, false).statusCode()).isEqualTo(401);
        var cross =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/csrf"))
                        .header("Origin", "https://evil.example")
                        .GET()
                        .build();
        assertThat(client.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
        String encrypted = crypto.encrypt("example-secret");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("example-secret");
        jdbc.update("UPDATE auth_account SET mfa_secret=? WHERE id=1", encrypted);
        var missing =
                new MockEnvironment()
                        .withProperty(
                                "singverse.auth.key-file", temp.resolve("missing.key").toString());
        assertThatThrownBy(() -> new AuthCrypto(store, mediaSources, missing))
                .hasMessageContaining("missing");
        Path wrong = temp.resolve("wrong.key");
        Files.writeString(wrong, Base64.getEncoder().encodeToString(AuthCrypto.random(32)));
        Files.setPosixFilePermissions(
                wrong, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(
                        () ->
                                new AuthCrypto(
                                        store,
                                        mediaSources,
                                        new MockEnvironment()
                                                .withProperty(
                                                        "singverse.auth.key-file",
                                                        wrong.toString())))
                .hasMessageContaining("does not match");
    }

    @Test
    void recoveryCodeConsumptionIsAtomicAcrossConcurrentChallenges() throws Exception {
        jdbc.update(
                "UPDATE auth_account SET mfa_secret=? WHERE id=1",
                crypto.encrypt("JBSWY3DPEHPK3PXP"));
        String recovery = "0123456789abcdef0123456789abcdef";
        jdbc.update("INSERT INTO auth_recovery (code_hash) VALUES (?)", AuthCrypto.hash(recovery));
        String first = service.issue("challenge").token(),
                second = service.issue("challenge").token();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks =
                    List.<java.util.concurrent.Callable<Boolean>>of(
                            () -> {
                                try {
                                    service.complete(first, recovery, "127.0.0.1");
                                    return true;
                                } catch (AuthFailure e) {
                                    return false;
                                }
                            },
                            () -> {
                                try {
                                    service.complete(second, recovery, "127.0.0.1");
                                    return true;
                                } catch (AuthFailure e) {
                                    return false;
                                }
                            });
            var results = executor.invokeAll(tasks);
            assertThat(
                            results.stream()
                                    .filter(
                                            f -> {
                                                try {
                                                    return f.get();
                                                } catch (Exception e) {
                                                    throw new RuntimeException(e);
                                                }
                                            })
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_recovery", Integer.class))
                .isZero();
    }

    @Test
    void cancellingSetupAndDisablingMfaRevokeAsExpected() throws Exception {
        login();
        request("/api/v1/system/parameters/mfa/setup", "POST", Map.of("password", "admin"), true);
        assertThat(
                        request("/api/v1/system/parameters/mfa/setup", "DELETE", null, true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(store.account().get("pending_secret")).isNull();
        jdbc.update(
                "UPDATE auth_account SET mfa_secret=? WHERE id=1",
                crypto.encrypt("JBSWY3DPEHPK3PXP"));
        String recovery = "0123456789abcdef0123456789abcdef";
        jdbc.update("INSERT INTO auth_recovery (code_hash) VALUES (?)", AuthCrypto.hash(recovery));
        assertThat(
                        request(
                                        "/api/v1/system/parameters/mfa/disable",
                                        "POST",
                                        Map.of("password", "admin", "code", recovery),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(store.account().get("mfa_secret")).isNull();
        assertThat(request("/api/v1/auth/session", "GET", null, false).statusCode()).isEqualTo(401);
        assertThat(body(login()).get("state").asText()).isEqualTo("authenticated");
    }

    @Test
    void portablePasswordAndEncryptedSecretMatchIndependentVectors() throws Exception {
        var fixture =
                json.readTree(
                        store.root().resolve("contracts/openapi/auth-test-vectors.json").toFile());
        assertThat(
                        store.passwords()
                                .matches(
                                        fixture.get("password").asText(),
                                        fixture.get("argon2idPhc").asText()))
                .isTrue();
        Path key = temp.resolve("fixture.key");
        Files.writeString(key, fixture.get("aes256KeyBase64").asText());
        Files.setPosixFilePermissions(
                key, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var independent =
                new AuthCrypto(
                        store,
                        mediaSources,
                        new MockEnvironment()
                                .withProperty("singverse.auth.key-file", key.toString()));
        assertThat(independent.decrypt(fixture.get("totpEncrypted").asText()))
                .isEqualTo(fixture.get("totpSecret").asText());
    }

    @Test
    void openApiListsEveryImplementedAuthOperation() throws Exception {
        var spec =
                json.readTree(
                        store.root().resolve("contracts/openapi/singverse.openapi.json").toFile());
        assertThat(spec.get("openapi").asText()).isEqualTo("3.1.0");
        int count = 0;
        for (var controller :
                List.of(
                        AuthController.class,
                        com.mynora.singverse.ai.AiController.class,
                        com.mynora.singverse.compute.ComputeNodeController.class,
                        com.mynora.singverse.media.classification.ClassificationController.class,
                        com.mynora.singverse.media.controller.MediaSourceController.class,
                        com.mynora.singverse.media.webdav.ScanTaskController.class,
                        com.mynora.singverse.media.controller.MediaSongController.class,
                        com.mynora.singverse.media.controller.MetadataController.class,
                        com.mynora.singverse.media.controller.FilenameParserController.class,
                        com.mynora.singverse.media.artist.ArtistController.class,
                        com.mynora.singverse.media.playlist.PlaylistController.class,
                        com.mynora.singverse.mobile.MobileLibraryController.class,
                        com.mynora.singverse.tv.TvController.class,
                        com.mynora.singverse.media.transcoding.TranscodeController.class,
                        com.mynora.singverse.media.transcoding.TranscodeInputController.class)) {
            var base =
                    (controller.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class)
                    ? controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0] : "");
            for (var method : controller.getDeclaredMethods()) {
                var mapping =
                        org.springframework.core.annotation.AnnotatedElementUtils
                                .findMergedAnnotation(
                                        method,
                                        org.springframework.web.bind.annotation.RequestMapping
                                                .class);
                if (mapping == null) continue;
                for (var path : mapping.value())
                    for (var verb : mapping.method()) {
                        assertThat(
                                        spec.path("paths")
                                                .path(base + path)
                                                .has(
                                                        verb.name()
                                                                .toLowerCase(
                                                                        java.util.Locale.ROOT)))
                                .as(method.getName())
                                .isTrue();
                        count++;
                    }
            }
        }
        int documented = 0;
        for (var item : spec.get("paths")) documented += item.size();
        assertThat(count).isEqualTo(documented);
    }

    @Test
    void mediaLocalCrudValidatesDirectoriesAndNeverDeletesFiles() throws Exception {
        login();
        Path directory = Files.createTempDirectory(temp, "songs-");
        Path original = Files.writeString(directory.resolve("song.mp4"), "original-media");
        var payload = Map.of("name", "家庭歌曲", "type", "LOCAL", "location", directory.toString());
        var created = request("/api/v1/media-sources", "POST", payload, true);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = body(created).path("id").asText();
        String path = "/api/v1/media-sources/" + id;
        assertThat(created.headers().firstValue("Location")).contains(path);
        assertThat(body(created).path("location").asText())
                .isEqualTo(directory.toRealPath().toString());
        assertThat(body(created).path("passwordConfigured").asBoolean()).isFalse();
        assertThat(request("/api/v1/media-sources", "POST", payload, true).statusCode())
                .isEqualTo(409);
        var list = body(request("/api/v1/media-sources?page=0&size=1", "GET", null, false));
        assertThat(list.path("totalElements").asInt()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(id);
        assertThat(java.time.Instant.parse(body(created).path("createdAt").asText()).toString())
                .endsWith("Z");
        assertThat(
                        request(
                                        path,
                                        "PUT",
                                        Map.of(
                                                "name",
                                                "已改名",
                                                "type",
                                                "LOCAL",
                                                "location",
                                                directory.toString()),
                                        true)
                                .statusCode())
                .isEqualTo(200);
        assertThat(body(request(path, "GET", null, false)).path("name").asText()).isEqualTo("已改名");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM media_source WHERE id=?", String.class, id))
                .isEqualTo("已改名");
        assertThat(request(path, "DELETE", null, true).statusCode()).isEqualTo(204);
        assertThat(request(path, "GET", null, false).statusCode()).isEqualTo(404);
        assertThat(request(path, "DELETE", null, true).statusCode()).isEqualTo(404);
        assertThat(Files.readString(original)).isEqualTo("original-media");
    }

    @Test
    void webdavCredentialsAreEncryptedRetainedReplacedAndCleared() throws Exception {
        login();
        String secret = "example-app-password";
        var created =
                request(
                        "/api/v1/media-sources",
                        "POST",
                        Map.of(
                                "name",
                                "远端",
                                "type",
                                "WEBDAV",
                                "location",
                                "https://nas.example.invalid/dav",
                                "username",
                                "singer",
                                "password",
                                secret),
                        true);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body())
                .doesNotContain(secret, "password_encrypted", "passwordEncrypted");
        String id = body(created).path("id").asText();
        String path = "/api/v1/media-sources/" + id;
        String encrypted =
                jdbc.queryForObject(
                        "SELECT password_encrypted FROM media_source WHERE id=?", String.class, id);
        assertThat(encrypted).doesNotContain(secret);
        assertThat(crypto.decrypt(encrypted, "singverse:webdav:v1")).isEqualTo(secret);
        assertThatThrownBy(() -> crypto.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
        assertThat(body(created).path("location").asText())
                .isEqualTo("https://nas.example.invalid/dav/");
        var update =
                new HashMap<String, Object>(
                        Map.of(
                                "name",
                                "改名",
                                "type",
                                "WEBDAV",
                                "location",
                                "https://nas.example.invalid/dav/",
                                "username",
                                "singer"));
        assertThat(request(path, "PUT", update, true).statusCode()).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT password_encrypted FROM media_source WHERE id=?",
                                String.class,
                                id))
                .isEqualTo(encrypted);
        update.put("location", "https://other.example.invalid/dav/");
        assertThat(request(path, "PUT", update, true).statusCode()).isEqualTo(400);
        update.put("password", "replacement");
        assertThat(request(path, "PUT", update, true).statusCode()).isEqualTo(200);
        assertThat(
                        crypto.decrypt(
                                jdbc.queryForObject(
                                        "SELECT password_encrypted FROM media_source WHERE id=?",
                                        String.class,
                                        id),
                                "singverse:webdav:v1"))
                .isEqualTo("replacement");
        assertThat(request("/api/v1/media-sources", "GET", null, false).body())
                .doesNotContain("replacement", secret, "v1:");
        assertThatThrownBy(
                        () ->
                                new AuthCrypto(
                                        store,
                                        mediaSources,
                                        new MockEnvironment()
                                                .withProperty(
                                                        "singverse.auth.key-file",
                                                        temp.resolve("absent-webdav.key")
                                                                .toString())))
                .hasMessageContaining("missing");
        update.put("password", "");
        assertThat(body(request(path, "PUT", update, true)).path("passwordConfigured").asBoolean())
                .isFalse();
    }

    @Test
    void mediaRejectsInvalidInputsAndEnforcesSessionAndCsrf() throws Exception {
        assertThat(request("/api/v1/media-sources", "GET", null, false).statusCode())
                .isEqualTo(401);
        login();
        assertThat(request("/api/v1/media-sources", "POST", Map.of(), false).statusCode())
                .isEqualTo(403);
        for (String location :
                List.of(
                        "relative/path",
                        temp.resolve("absent").toString(),
                        Files.createTempFile(temp, "file", ".txt").toString())) {
            var response =
                    request(
                            "/api/v1/media-sources",
                            "POST",
                            Map.of("name", "本地", "type", "LOCAL", "location", location),
                            true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .contains("application/problem+json");
        }
        for (String location :
                List.of(
                        "file:///tmp/songs",
                        "https://name:secret@example.invalid/dav",
                        "https://example.invalid/dav?token=secret",
                        "https://example.invalid/#fragment"))
            assertThat(
                            request(
                                            "/api/v1/media-sources",
                                            "POST",
                                            Map.of(
                                                    "name",
                                                    "远端",
                                                    "type",
                                                    "WEBDAV",
                                                    "location",
                                                    location),
                                            true)
                                    .statusCode())
                    .isEqualTo(400);
        assertThat(request("/api/v1/media-sources", "POST", Map.of(), true).statusCode())
                .isEqualTo(400);
        assertThat(request("/api/v1/media-sources?page=-1", "GET", null, false).statusCode())
                .isEqualTo(400);
        assertThat(request("/api/v1/media-sources?size=101", "GET", null, false).statusCode())
                .isEqualTo(400);
        assertThat(request("/api/v1/media-sources?page=wrong", "GET", null, false).statusCode())
                .isEqualTo(400);
    }

    @Test
    void rfc6238Vectors() throws Exception {
        var generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 8);
        assertThat(generator.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59 / 30))
                .isEqualTo("94287082");
        assertThat(generator.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 1111111109L / 30))
                .isEqualTo("07081804");
    }
}
