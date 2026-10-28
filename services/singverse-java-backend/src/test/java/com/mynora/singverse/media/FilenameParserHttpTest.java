package com.mynora.singverse.media;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.controller.FilenameParserController;
import com.mynora.singverse.media.repository.FilenameParserConfigRepository;
import com.mynora.singverse.infrastructure.database.DatabaseInitializer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
class FilenameParserHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void props(DynamicPropertyRegistry properties) {
        properties.add("singverse.sqlite.path", () -> temp.resolve("rules.sqlite").toString());
        properties.add("singverse.auth.key-file", () -> temp.resolve("master.key").toString());
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired com.mynora.singverse.media.repository.FilenameParserConfigMapper configMapper;
    @Autowired ObjectMapper json;
    @Autowired DatabaseInitializer database;
    HttpClient client;
    static final String BASE = "/api/v1/filename-parser";

    @BeforeEach void reset() {
        db.update("DELETE FROM filename_parser_config");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    HttpResponse<String> request(String path, String method, Object data, boolean csrf) throws Exception {
        String token = csrf ? body(request("/api/v1/auth/csrf", "GET", null, false)).get("token").asText() : null;
        return send(path, method, data, token);
    }
    HttpResponse<String> send(String path, String method, Object data, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("X-CSRF-Token", token);
        if (data != null) builder.header("Content-Type", "application/json");
        builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    void login() throws Exception {
        assertThat(request("/api/v1/auth/login", "POST", Map.of("username", "admin", "password", "admin"), true).statusCode()).isEqualTo(200);
    }
    Map<String, Object> rule(String expression) { return Map.of("id", "custom", "name", "反向命名", "type", "TEMPLATE", "enabled", true, "expression", expression); }

    @Test void authenticationCsrfAndDefaultConfigurationAreEnforced() throws Exception {
        assertThat(request(BASE + "/rules", "GET", null, false).statusCode()).isEqualTo(401);
        assertThat(request(BASE + "/preview", "POST", Map.of("filename", "A-B.mkv"), true).statusCode()).isEqualTo(401);
        login();
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", List.of()), false).statusCode()).isEqualTo(403);
        assertThat(request(BASE + "/preview", "POST", Map.of("filename", "A-B.mkv"), false).statusCode()).isEqualTo(403);
        var defaults = body(request(BASE + "/rules", "GET", null, false));
        assertThat(defaults.get("revision").asLong()).isZero();
        assertThat(defaults.get("rules").size()).isGreaterThan(5);
        assertThat(defaults.get("builtinRules").size()).isEqualTo(defaults.get("rules").size());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
    }

    @Test void draftPreviewDoesNotPersistAndSavedConfigurationSurvivesNewRepositoryAndBrowser() throws Exception {
        login();
        var draft = List.of(rule("{title}-{artist}"));
        var preview = body(request(BASE + "/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv", "rules", draft), true));
        assertThat(preview.at("/result/title").asText()).isEqualTo("晴天");
        assertThat(preview.at("/result/artists/0").asText()).isEqualTo("周杰伦");
        assertThat(preview.at("/selectedRule/id").asText()).isEqualTo("custom");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
        var saved = request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", draft), true);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(body(saved).get("revision").asLong()).isEqualTo(1);
        var fresh = new FilenameParserConfigRepository(configMapper, json, database).read();
        assertThat(fresh.revision()).isEqualTo(1);
        assertThat(fresh.rules().getFirst().expression()).isEqualTo("{title}-{artist}");
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        login();
        assertThat(body(request(BASE + "/rules", "GET", null, false)).get("revision").asLong()).isEqualTo(1);
        var persisted = body(request(BASE + "/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv"), true));
        assertThat(persisted.at("/result/title").asText()).isEqualTo("晴天");
        assertThat(persisted.at("/result/artists/0").asText()).isEqualTo("周杰伦");
        assertThat(persisted.at("/result/parseStatus").asText()).isEqualTo("SUCCESS");
    }

    @Test void invalidRulesAreLocatedAndNeverReplaceSavedRules() throws Exception {
        login();
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", List.of(rule("{title}-{artist}"))), true).statusCode()).isEqualTo(200);
        var bad = Map.of("id", "bad-regex", "name", "无效规则", "type", "REGEX", "enabled", true, "expression", "[");
        for (String endpoint : List.of("/rules", "/preview")) {
            var response = request(BASE + endpoint, endpoint.equals("/rules") ? "PUT" : "POST",
                    Map.of("revision", 1, "filename", "A-B.mkv", "rules", List.of(bad)), true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("content-type").orElse("")).startsWith("application/problem+json");
            var problem = body(response);
            assertThat(problem.get("ruleId").asText()).isEqualTo("bad-regex");
            assertThat(problem.get("ruleIndex").asInt()).isZero();
            assertThat(problem.get("field").asText()).isEqualTo("expression");
        }
        assertThat(body(request(BASE + "/rules", "GET", null, false)).get("revision").asLong()).isEqualTo(1);
        var wrongType = Map.of("id", "bad", "name", "坏类型", "type", "SCRIPT", "enabled", true);
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 1, "rules", List.of(wrongType)), true).statusCode()).isEqualTo(400);
    }

    @Test void staleRevisionAndConcurrentWritersCannotOverwriteEachOther() throws Exception {
        login();
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", List.of()), true).statusCode()).isEqualTo(200);
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", List.of(rule("{title}-{artist}"))), true).statusCode()).isEqualTo(409);
        String csrf = body(request("/api/v1/auth/csrf", "GET", null, false)).get("token").asText();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> send(BASE + "/rules", "PUT", Map.of("revision", 1, "rules", List.of(rule("{title}-{artist}"))), csrf));
            var second = executor.submit(() -> send(BASE + "/rules", "PUT", Map.of("revision", 1, "rules", List.of(rule("{artist}-{title}"))), csrf));
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(body(request(BASE + "/rules", "GET", null, false)).get("revision").asLong()).isEqualTo(2);
    }

    @Test void noMatchIsARegularResponseAndEmptyDraftIsNotOmitted() throws Exception {
        login();
        var response = request(BASE + "/preview", "POST", Map.of("filename", "A-B.mkv", "rules", List.of()), true);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = body(response);
        assertThat(result.get("selectedRule").isNull()).isTrue();
        assertThat(result.at("/result/parseStatus").asText()).isEqualTo("FAILED");
        assertThat(result.get("attempts")).isEmpty();
        assertThat(request(BASE + "/preview", "POST", Map.of("filename", "x".repeat(1025)), true).statusCode()).isEqualTo(400);
    }

    @Test void openApiListsEveryRuleEngineOperation() throws Exception {
        var spec = json.readTree(database.root.resolve("contracts/openapi/singverse.openapi.json").toFile());
        for (var method : FilenameParserController.class.getDeclaredMethods()) {
            var mapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method, org.springframework.web.bind.annotation.RequestMapping.class);
            if (mapping == null) continue;
            for (var path : mapping.value()) for (var verb : mapping.method())
                assertThat(spec.path("paths").path(BASE + path).has(verb.name().toLowerCase(Locale.ROOT))).as(method.getName()).isTrue();
        }
    }
    Map<String, Object> definitionInput(String name, String expression) {
        return Map.of("name", name, "type", "TEMPLATE", "expression", expression);
    }
    Map<String, Object> entry(String id, boolean enabled) { return Map.of("ruleId", id, "enabled", enabled); }
    JsonNode workspace() throws Exception { return body(request(BASE + "/profiles", "GET", null, false)); }
    JsonNode createDefinition(long revision, String name, String expression) throws Exception {
        var response = request(BASE + "/rule-definitions", "POST", Map.of("revision", revision, "definition", definitionInput(name, expression)), true);
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response);
    }
    JsonNode createProfile(long revision, String name, List<Map<String, Object>> entries) throws Exception {
        var response = request(BASE + "/profiles", "POST", Map.of("revision", revision, "profile", Map.of("name", name, "entries", entries)), true);
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response);
    }

    @Test void legacyArrayIsProjectedWithoutWritingAndFirstMutationAtomicallyUpgrades() throws Exception {
        login();
        List<Map<String, Object>> legacy = List.of(rule("{title}-{artist}"), Map.of("id", "off", "name", "停用", "type", "TEMPLATE", "enabled", false, "expression", "{artist}-{title}"));
        String raw = json.writeValueAsString(legacy);
        db.update("INSERT INTO filename_parser_config (id,revision,rules_json,updated_at) VALUES (1,7,?,?)", raw, "2026-10-27T00:00:00Z");
        var projected = workspace();
        assertThat(projected.at("/profiles/0/id").asText()).isEqualTo("default");
        assertThat(projected.at("/profiles/0/entries/0/ruleId").asText()).isEqualTo("custom");
        assertThat(projected.at("/profiles/0/entries/1/enabled").asBoolean()).isFalse();
        assertThat(projected.at("/definitions/0/expression").asText()).isEqualTo("{title}-{artist}");
        assertThat(db.queryForObject("SELECT rules_json FROM filename_parser_config", String.class)).isEqualTo(raw);
        assertThat(body(request(BASE + "/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv"), true)).at("/result/title").asText()).isEqualTo("晴天");
        var updated = request(BASE + "/profiles/default", "PUT", Map.of("revision", 7, "profile", Map.of("name", "改名默认", "entries", List.of(entry("custom", true), entry("off", false)))), true);
        assertThat(updated.statusCode()).isEqualTo(200);
        var stored = json.readTree(db.queryForObject("SELECT rules_json FROM filename_parser_config", String.class));
        assertThat(stored.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(stored.at("/definitions/0/id").asText()).isEqualTo("custom");
        assertThat(stored.at("/profiles/0/entries/1/enabled").asBoolean()).isFalse();
        var fresh = new FilenameParserConfigRepository(configMapper, json, database).read();
        assertThat(fresh.revision()).isEqualTo(8);
        assertThat(fresh.document().profiles().getFirst().name()).isEqualTo("改名默认");
        assertThat(fresh.rules().getFirst().id()).isEqualTo("custom");
    }

    @Test void sharedDefinitionUpdatesAffectAllReferencingProfilesAndDeletionReportsReferences() throws Exception {
        login();
        var created = createDefinition(0, "共享规则", "{artist}-{title}");
        String ruleId = created.get("changedId").asText();
        var first = createProfile(1, "方案甲", List.of(entry(ruleId, true)));
        String firstId = first.get("changedId").asText();
        var second = createProfile(2, "方案乙", List.of(entry(ruleId, true)));
        String secondId = second.get("changedId").asText();
        var changed = request(BASE + "/rule-definitions/" + ruleId, "PUT", Map.of("revision", 3, "definition", definitionInput("共享反向", "{title}-{artist}")), true);
        assertThat(changed.statusCode()).isEqualTo(200);
        for (String id : List.of(firstId, secondId)) {
            var preview = body(request(BASE + "/profiles/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv", "profileId", id), true));
            assertThat(preview.at("/preview/result/title").asText()).isEqualTo("晴天");
            assertThat(preview.at("/preview/result/artists/0").asText()).isEqualTo("周杰伦");
            assertThat(preview.at("/preview/selectedRule/name").asText()).isEqualTo("共享反向");
        }
        var blocked = request(BASE + "/rule-definitions/" + ruleId + "?revision=4", "DELETE", null, true);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(body(blocked).get("referencedBy")).as(blocked.body()).hasSize(2);
        assertThat(body(blocked).get("detail").asText()).contains("方案甲", "方案乙");
        assertThat(request(BASE + "/profiles/" + firstId + "?revision=4", "DELETE", null, true).statusCode()).isEqualTo(200);
        assertThat(request(BASE + "/profiles/" + secondId + "?revision=5", "DELETE", null, true).statusCode()).isEqualTo(200);
        assertThat(request(BASE + "/rule-definitions/" + ruleId + "?revision=6", "DELETE", null, true).statusCode()).isEqualTo(200);
        assertThat(workspace().get("revision").asLong()).isEqualTo(7);
    }

    @Test void profilesCanBeCopiedRenamedEmptiedAndOrderedWithoutChangingTheLibrary() throws Exception {
        login();
        var created = createDefinition(0, "反向", "{title}-{artist}");
        String reverse = created.get("changedId").asText();
        var forwardCreated = createDefinition(1, "正向", "{artist}-{title}");
        String forward = forwardCreated.get("changedId").asText();
        var entries = List.of(entry(forward, false), entry(reverse, true));
        var profileCreated = createProfile(2, "反向方案", entries);
        String id = profileCreated.get("changedId").asText();
        var copy = createProfile(3, "反向方案副本", entries);
        String copyId = copy.get("changedId").asText();
        assertThat(copyId).isNotEqualTo(id);
        var preview = body(request(BASE + "/profiles/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv", "profileId", copyId), true));
        assertThat(preview.at("/preview/selectedRule/id").asText()).isEqualTo(reverse);
        var changed = request(BASE + "/profiles/" + id, "PUT", Map.of("revision", 4, "profile", Map.of("name", "改名方案", "entries", List.of(entry(forward, true), entry(reverse, true)))), true);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(body(request(BASE + "/profiles/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv", "profileId", id), true)).at("/preview/selectedRule/id").asText()).isEqualTo(forward);
        assertThat(request(BASE + "/profiles/default?revision=5", "DELETE", null, true).statusCode()).isEqualTo(409);
        assertThat(request(BASE + "/profiles/default", "PUT", Map.of("revision", 5, "profile", Map.of("name", "空默认", "entries", List.of())), true).statusCode()).isEqualTo(200);
        assertThat(body(request(BASE + "/preview", "POST", Map.of("filename", "A-B.mkv"), true)).get("selectedRule").isNull()).isTrue();
        assertThat(workspace().get("definitions")).hasSize(14);
    }

    @Test void invalidAndStaleDraftReferencesNeverWriteOrDisappearSilently() throws Exception {
        login();
        var before = workspace();
        var empty = request(BASE + "/profiles/preview", "POST", Map.of("filename", "A-B.mkv", "revision", 0, "entries", List.of()), true);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(body(empty).at("/preview/selectedRule").isNull()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
        String valid = before.at("/definitions/0/id").asText();
        for (List<Map<String, Object>> entries : List.of(List.of(entry("missing", true)), List.of(entry(valid, true), entry(valid, false)))) {
            for (String endpoint : List.of("/profiles", "/profiles/preview")) {
                Object input = endpoint.equals("/profiles") ? Map.of("revision", 0, "profile", Map.of("name", "无效方案", "entries", entries))
                        : Map.of("filename", "A-B.mkv", "revision", 0, "entries", entries);
                var response = request(BASE + endpoint, "POST", input, true);
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(body(response).get("field").asText()).isEqualTo("entries");
            }
        }
        var invalid = request(BASE + "/rule-definitions", "POST", Map.of("revision", 0, "definition", definitionInput("坏规则", "{artist}{title}")), true);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM filename_parser_config", Integer.class)).isZero();
        createProfile(0, "新方案", List.of());
        assertThat(request(BASE + "/profiles/preview", "POST", Map.of("filename", "A-B.mkv", "revision", 0, "entries", List.of()), true).statusCode()).isEqualTo(409);
        assertThat(request(BASE + "/profiles/preview", "POST", Map.of("filename", "A-B.mkv", "profileId", "default", "entries", List.of()), true).statusCode()).isEqualTo(400);
    }

    @Test void compatibilitySaveMergesDefinitionsAndPreservesOtherProfileReferences() throws Exception {
        login();
        var saved = request(BASE + "/rules", "PUT", Map.of("revision", 0, "rules", List.of(rule("{title}-{artist}"))), true);
        assertThat(saved.statusCode()).isEqualTo(200);
        var additional = createProfile(1, "复用", List.of(entry("custom", true)));
        String profileId = additional.get("changedId").asText();
        assertThat(request(BASE + "/rules", "PUT", Map.of("revision", 2, "rules", List.of()), true).statusCode()).isEqualTo(200);
        assertThat(body(request(BASE + "/rules", "GET", null, false)).get("rules")).isEmpty();
        assertThat(body(request(BASE + "/profiles/preview", "POST", Map.of("filename", "晴天-周杰伦.mkv", "profileId", profileId), true)).at("/preview/result/title").asText()).isEqualTo("晴天");
        var builtinEdit = request(BASE + "/rule-definitions/builtin-book", "PUT", Map.of("revision", 3, "definition", definitionInput("覆盖内置", "{artist}-{title}")), true);
        assertThat(builtinEdit.statusCode()).isEqualTo(409);
        assertThat(request(BASE + "/rule-definitions/builtin-book?revision=3", "DELETE", null, true).statusCode()).isEqualTo(409);
    }

    @Test void deletionAndNewReferenceRaceCannotCreateDanglingReferences() throws Exception {
        login();
        String id = createDefinition(0, "竞争规则", "{artist}-{title}").get("changedId").asText();
        String csrf = body(request("/api/v1/auth/csrf", "GET", null, false)).get("token").asText();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delete = executor.submit(() -> send(BASE + "/rule-definitions/" + id + "?revision=1", "DELETE", null, csrf));
            var reference = executor.submit(() -> send(BASE + "/profiles", "POST", Map.of("revision", 1, "profile", Map.of("name", "竞争方案", "entries", List.of(entry(id, true)))), csrf));
            int deleted = delete.get().statusCode();
            int added = reference.get().statusCode();
            assertThat(List.of(deleted, added)).contains(409);
            assertThat(deleted == 200 || added == 201).isTrue();
        }
        var after = workspace();
        var defined = new HashSet<String>();
        after.get("definitions").forEach(d -> defined.add(d.get("id").asText()));
        for (var profile : after.get("profiles")) for (var item : profile.get("entries"))
            assertThat(defined).contains(item.get("ruleId").asText());
    }

    @Test void newEndpointsRetainSessionAndCsrfProtection() throws Exception {
        for (String path : List.of("/rule-definitions", "/profiles")) {
            assertThat(request(BASE + path, "GET", null, false).statusCode()).isEqualTo(401);
            assertThat(request(BASE + path, "POST", Map.of(), true).statusCode()).isEqualTo(401);
        }
        login();
        for (String path : List.of("/rule-definitions", "/profiles"))
            assertThat(request(BASE + path, "POST", Map.of(), false).statusCode()).isEqualTo(403);
        assertThat(request(BASE + "/profiles/preview", "POST", Map.of(), false).statusCode()).isEqualTo(403);
        assertThat(request(BASE + "/profiles/default?revision=0", "DELETE", null, false).statusCode()).isEqualTo(403);
    }

    @Test void invalidStoredMigrationDataIsNeverResetOrOverwritten() throws Exception {
        login();
        for (String raw : List.of("[{\"id\":\"invalid\"}]", "{\"schemaVersion\":99}")) {
            db.update("DELETE FROM filename_parser_config");
            db.update("INSERT INTO filename_parser_config (id,revision,rules_json,updated_at) VALUES (1,4,?,?)", raw, "2026-10-27T00:00:00Z");
            assertThat(request(BASE + "/profiles", "GET", null, false).statusCode()).isEqualTo(500);
            assertThat(request(BASE + "/profiles", "POST", Map.of("revision", 4, "profile", Map.of("name", "不能覆盖", "entries", List.of())), true).statusCode()).isEqualTo(500);
            assertThat(db.queryForObject("SELECT rules_json FROM filename_parser_config", String.class)).isEqualTo(raw);
            assertThat(db.queryForObject("SELECT revision FROM filename_parser_config", Long.class)).isEqualTo(4);
        }
    }

}
