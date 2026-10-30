package com.mynora.singverse.compute;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.infrastructure.compute.MediaTagsClient;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite") @DirtiesContext
class ComputeNodeHttpTest {
    static final String TOKEN="isolated-compute-fixture-12345678901234567890";
    @TempDir static Path temp;
    static com.sun.net.httpserver.HttpServer fixture;
    static volatile int status=200;
    static volatile String payload="{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[\"media-tags\"]}";
    static volatile String requestedPath;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) throws Exception {
        Files.writeString(temp.resolve("compute.token"),TOKEN);
        fixture=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/",e->{
            if(!("Bearer "+TOKEN).equals(e.getRequestHeaders().getFirst("Authorization"))){e.sendResponseHeaders(401,-1);e.close();return;}
            var body=e.getRequestBody().readAllBytes();
            String result=payload;
            if(e.getRequestURI().getPath().endsWith("/tags")){requestedPath=new ObjectMapper().readTree(body).path("path").asText();result="{\"title\":\"文件标题\",\"artists\":[\"文件歌手\"],\"album\":null,\"releaseDate\":null}";}
            byte[] bytes=result.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });fixture.start();
        p.add("singverse.sqlite.path",()->temp.resolve("compute.db").toString());p.add("singverse.auth.key-file",()->temp.resolve("auth.key").toString());p.add("singverse.classification.worker-enabled",()->false);
        p.add("singverse.compute.base-url",ComputeNodeHttpTest::endpoint);p.add("singverse.compute.token-file",()->temp.resolve("compute.token").toString());
    }
    static String endpoint(){return "http://127.0.0.1:"+fixture.getAddress().getPort();}
    @AfterAll static void stop(){if(fixture!=null)fixture.stop(0);}
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired MediaTagsClient tags;
    @Autowired com.mynora.singverse.auth.repository.AuthStore store;
    @Autowired com.mynora.singverse.media.repository.MediaSourceMapper media;
    @Autowired com.mynora.singverse.media.classification.ClassificationMapper classification;
    @Autowired com.mynora.singverse.ai.AiMapper ai;
    @Autowired ComputeNodeMapper compute;
    HttpClient client;
    @BeforeEach void reset(){status=200;payload="{\"service\":\"singverse-compute\",\"apiVersion\":\"1\",\"capabilities\":[\"media-tags\"]}";requestedPath=null;db.update("UPDATE compute_node_settings SET mode='AUTO',endpoint='',secret_encrypted=NULL,mappings_json='[]',revision=0 WHERE id=1");client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> req(String path,String method,Object input,boolean csrf)throws Exception{
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf)r.header("X-CSRF-Token",json.readTree(req("/api/v1/auth/csrf","GET",null,false).body()).path("token").asText());
        if(input!=null)r.header("Content-Type","application/json");
        return client.send(r.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path,String method,Object input)throws Exception{var r=req(path,method,input,true);assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(200);return json.readTree(r.body());}
    void login()throws Exception{ok("/api/v1/auth/login","POST",Map.of("username","admin","password","admin"));}
    Map<String,Object> input(String mode,long revision){var v=new HashMap<String,Object>();v.put("mode",mode);v.put("endpoint",endpoint());v.put("token",TOKEN);v.put("clearToken",false);v.put("mappings",List.of(Map.of("localRoot","/local","remoteRoot","/media"),Map.of("localRoot","/local/special","remoteRoot","/special")));v.put("revision",revision);return v;}
    @Test void automaticDiscoveryAuthAndDraftTestDoNotModifySettings()throws Exception{
        assertThat(req("/api/v1/compute-node","GET",null,false).statusCode()).isEqualTo(401);login();
        var defaults=ok("/api/v1/compute-node","GET",null);assertThat(defaults.path("mode").asText()).isEqualTo("AUTO");assertThat(defaults.path("automaticEndpoint").asText()).isEqualTo(endpoint());assertThat(defaults.path("automaticHasToken").asBoolean()).isTrue();assertThat(defaults.toString()).doesNotContain(TOKEN);
        assertThat(req("/api/v1/compute-node","PUT",input("MANUAL",0),false).statusCode()).isEqualTo(403);
        var result=ok("/api/v1/compute-node/test","POST",input("AUTO",0));assertThat(result.path("status").asText()).isEqualTo("AVAILABLE");assertThat(result.path("capabilities").get(0).asText()).isEqualTo("media-tags");assertThat(result.toString()).doesNotContain(TOKEN);
        assertThat(ok("/api/v1/compute-node","GET",null).path("revision").asLong()).isZero();
    }
    @Test void encryptedCredentialRetentionRevisionAndChangedAddressProtection()throws Exception{
        login();var saved=ok("/api/v1/compute-node","PUT",input("MANUAL",0));assertThat(saved.path("hasToken").asBoolean()).isTrue();assertThat(saved.toString()).doesNotContain(TOKEN,"secret_encrypted");
        assertThat(db.queryForObject("SELECT secret_encrypted FROM compute_node_settings",String.class)).isNotEqualTo(TOKEN).doesNotContain(TOKEN);
        var missing=temp.resolve("missing-master.key");
        assertThatThrownBy(()->new com.mynora.singverse.auth.crypto.AuthCrypto(store,media,new org.springframework.mock.env.MockEnvironment().withProperty("singverse.auth.key-file",missing.toString()),classification,ai,compute)).hasMessageContaining("missing");
        assertThat(Files.exists(missing)).isFalse();
        var edit=input("MANUAL",1);edit.put("token","");ok("/api/v1/compute-node","PUT",edit);assertThat(req("/api/v1/compute-node","PUT",edit,true).statusCode()).isEqualTo(409);
        edit.put("revision",2);edit.put("endpoint","http://127.0.0.1:1");assertThat(req("/api/v1/compute-node/test","POST",edit,true).statusCode()).isEqualTo(400);assertThat(req("/api/v1/compute-node","PUT",edit,true).statusCode()).isEqualTo(400);
        edit.put("clearToken",true);assertThat(ok("/api/v1/compute-node","PUT",edit).path("hasToken").asBoolean()).isFalse();assertThat(tags.read(Path.of("/local/a.mp3"))).isNull();
    }
    @Test void pathMappingAndScanSnapshotStayOnTheirOriginalConnection()throws Exception{
        login();ok("/api/v1/compute-node","PUT",input("MANUAL",0));var session=tags.session();
        assertThat(session.apply(Path.of("/local/special/中文.mp4"))).contains("文件标题");assertThat(requestedPath).isEqualTo("/special/中文.mp4");
        assertThat(session.apply(Path.of("/local/other.mp3"))).isNotNull();assertThat(requestedPath).isEqualTo("/media/other.mp3");
        ok("/api/v1/compute-node","PUT",input("DISABLED",1));assertThat(tags.read(Path.of("/local/new.mp3"))).isNull();assertThat(session.apply(Path.of("/local/old.mp3"))).isNotNull();assertThat(requestedPath).isEqualTo("/media/old.mp3");
    }
    @Test void errorsAreSafeAndDisabledModeMakesNoCall()throws Exception{
        login();status=403;assertThat(ok("/api/v1/compute-node/test","POST",input("AUTO",0)).path("status").asText()).isEqualTo("AUTH_FAILED");
        status=302;assertThat(ok("/api/v1/compute-node/test","POST",input("AUTO",0)).path("status").asText()).isEqualTo("UNAVAILABLE");
        status=200;payload="{\"service\":\"other\",\"capabilities\":[]}";assertThat(ok("/api/v1/compute-node/test","POST",input("AUTO",0)).path("status").asText()).isEqualTo("INCOMPATIBLE");
        assertThat(ok("/api/v1/compute-node/test","POST",input("DISABLED",0)).path("status").asText()).isEqualTo("DISABLED");
        var edit=input("MANUAL",0);edit.put("endpoint","http://8.8.8.8");assertThat(req("/api/v1/compute-node/test","POST",edit,true).statusCode()).isEqualTo(400);
        edit.put("endpoint","http://127.0.0.1:1");assertThat(ok("/api/v1/compute-node/test","POST",edit).path("status").asText()).isEqualTo("UNAVAILABLE");
        edit.put("endpoint",endpoint());edit.put("token","invalid-fixture-12345678901234567890");assertThat(ok("/api/v1/compute-node/test","POST",edit).path("status").asText()).isEqualTo("AUTH_FAILED");
        for(String endpoint:List.of("file:///etc/passwd","http://user:pass@localhost","http://localhost/media","http://169.254.169.254")) {edit.put("endpoint",endpoint);assertThat(req("/api/v1/compute-node/test","POST",edit,true).statusCode()).isEqualTo(400);}
        edit.put("endpoint",endpoint());edit.put("mappings",List.of(Map.of("localRoot","relative","remoteRoot","/media")));assertThat(req("/api/v1/compute-node","PUT",edit,true).statusCode()).isEqualTo(400);
    }
}
