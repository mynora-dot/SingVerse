package com.mynora.singverse.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.auth.repository.AuthStore;
import com.mynora.singverse.media.repository.MediaSourceMapper;
import com.mynora.singverse.media.classification.ClassificationMapper;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite") @DirtiesContext
class AiSettingsHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path",()->temp.resolve("ai.db").toString());p.add("singverse.auth.key-file",()->temp.resolve("auth.key").toString());p.add("singverse.classification.worker-enabled",()->false);
    }
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @Autowired AuthStore store;@Autowired MediaSourceMapper media;@Autowired ClassificationMapper classification;@Autowired AiMapper ai;@Autowired AiSettingsService settings;
    HttpClient client;
    @BeforeEach void reset(){db.update("UPDATE ai_feature SET model_service_id=NULL,search_service_id=NULL,enabled=0");db.update("DELETE FROM ai_service");db.update("DELETE FROM classification_task");client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> req(String path,String method,Object input,boolean csrf)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf)r.header("X-CSRF-Token",json.readTree(req("/api/v1/auth/csrf","GET",null,false).body()).path("token").asText());
        if(input!=null)r.header("Content-Type","application/json");
        return client.send(r.method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path,String method,Object v)throws Exception{var r=req(path,method,v,true);assertThat(r.statusCode()).withFailMessage(r.body()).isBetween(200,201);return json.readTree(r.body());}
    void login()throws Exception{ok("/api/v1/auth/login","POST",Map.of("username","admin","password","admin"));}
    Map<String,Object> input(String name,String protocol){var v=new HashMap<String,Object>();v.put("name",name);v.put("provider","custom");v.put("protocol",protocol);v.put("endpoint","https://example.test/api");v.put("model","fixture-model");v.put("outputMode","JSON_SCHEMA");v.put("apiKey","fixture-secret");v.put("clearKey",false);v.put("enabled",true);v.put("timeoutSeconds",5);v.put("maxResults",5);v.put("revision",0);return v;}
    @Test void authCrudBindingRevisionAndSecretsAreProtected()throws Exception {
        assertThat(req("/api/v1/ai/models","GET",null,false).statusCode()).isEqualTo(401);login();
        assertThat(req("/api/v1/ai/models","POST",input("服务","RESPONSES"),false).statusCode()).isEqualTo(403);
        var saved=ok("/api/v1/ai/models","POST",input("模型 A","RESPONSES"));String id=saved.path("id").asText();
        assertThat(saved.toString()).doesNotContain("fixture-secret","encryptedKey","credentialPurpose");assertThat(saved.path("hasKey").asBoolean()).isTrue();
        var feature=ok("/api/v1/ai/features","GET",null).get(0);assertThat(feature.path("id").asText()).isEqualTo("classification");
        var binding=new AiDtos.Feature("classification",id,null,true,4,feature.path("revision").asLong());ok("/api/v1/ai/features/classification","PUT",binding);
        assertThat(req("/api/v1/ai/models/"+id,"DELETE",null,true).statusCode()).isEqualTo(409);
        var edit=input("模型 B","RESPONSES");edit.put("apiKey","");edit.put("revision",saved.path("revision").asLong());
        var updated=ok("/api/v1/ai/models/"+id,"PUT",edit);assertThat(updated.path("hasKey").asBoolean()).isTrue();
        assertThat(req("/api/v1/ai/models/"+id,"PUT",edit,true).statusCode()).isEqualTo(409);
        edit.put("revision",updated.path("revision").asLong());edit.put("endpoint","https://other.test/api");assertThat(req("/api/v1/ai/models/"+id,"PUT",edit,true).statusCode()).isEqualTo(400);
        edit.put("clearKey",true);var cleared=ok("/api/v1/ai/models/"+id,"PUT",edit);assertThat(cleared.path("hasKey").asBoolean()).isFalse();
        edit.put("revision",cleared.path("revision").asLong());edit.put("enabled",false);assertThat(req("/api/v1/ai/models/"+id,"PUT",edit,true).statusCode()).isEqualTo(409);
        var current=ok("/api/v1/classification/settings","GET",null);assertThat(current.path("serviceId").asText()).isEqualTo(id);assertThat(current.path("model").asText()).isEqualTo("fixture-model");
        assertThat(current.toString()).doesNotContain("fixture-secret");
    }
    @Test void modelChangesPauseTasksAndSnapshotKeepsCredentials()throws Exception {
        login();var saved=ok("/api/v1/ai/models","POST",input("模型","CHAT_COMPLETIONS"));String id=saved.path("id").asText();
        long rev=ok("/api/v1/ai/features","GET",null).get(0).path("revision").asLong();ok("/api/v1/ai/features/classification","PUT",new AiDtos.Feature("classification",id,null,true,3,rev));
        var snapshot=db.queryForMap("SELECT secret_encrypted,credential_purpose FROM ai_service WHERE id=?",id);
        var frozen=settings.snapshot(id);
        var frozenConfig=new com.mynora.singverse.media.classification.ClassificationDtos.ModelConfig(true,frozen.endpoint(),frozen.model(),frozen.encryptedKey(),frozen.timeoutSeconds(),3,0,frozen.protocol(),frozen.outputMode(),frozen.credentialPurpose(),id);
        String taskJson=json.writeValueAsString(frozenConfig);
        db.update("INSERT INTO classification_task(id,status,config_json,vocabulary_json,dimensions_json,automatic_json,created_at,updated_at,next_at) VALUES('snapshot-task','RUNNING',?,'[]','[]','[]','','','')",taskJson);
        var edit=input("模型新版","CHAT_COMPLETIONS");edit.put("revision",saved.path("revision").asLong());edit.put("apiKey","replacement-secret");ok("/api/v1/ai/models/"+id,"PUT",edit);
        assertThat(db.queryForObject("SELECT status FROM classification_task WHERE id='snapshot-task'",String.class)).isEqualTo("PAUSED");
        var crypto=new AuthCrypto(store,media,new MockEnvironment().withProperty("singverse.auth.key-file",temp.resolve("auth.key").toString()),classification,ai);
        assertThat(db.queryForObject("SELECT config_json FROM classification_task WHERE id='snapshot-task'",String.class)).isEqualTo(taskJson);
        assertThat(crypto.decrypt(snapshot.get("secret_encrypted").toString(),snapshot.get("credential_purpose").toString())).isEqualTo("fixture-secret");
        assertThatThrownBy(()->new AuthCrypto(store,media,new MockEnvironment().withProperty("singverse.auth.key-file",temp.resolve("missing.key").toString()),classification,ai)).hasMessageContaining("missing");
    }
    @Test void defaultSearchBindingAndRateLimitCooldownAreIndependentOfClassification()throws Exception {
        login();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var fixture=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fixture.createContext("/",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(429,-1);exchange.close();});fixture.start();
        try {
            var v=input("搜索","CUSTOM-SEARCH");v.put("provider","custom-search");v.put("model","");v.put("outputMode","");v.put("endpoint","http://127.0.0.1:"+fixture.getAddress().getPort()+"/search");
            var saved=ok("/api/v1/ai/search-services","POST",v);String id=saved.path("id").asText();
            var defaults=ok("/api/v1/ai/features","GET",null).get(1);
            var binding=ok("/api/v1/ai/features/default-search","PUT",new AiDtos.Feature("default-search",null,id,true,3,defaults.path("revision").asLong()));
            assertThat(calls).hasValue(0);
            assertThat(req("/api/v1/ai/search-services/"+id,"DELETE",null,true).statusCode()).isEqualTo(409);
            for(int i=0;i<2;i++)assertThat(req("/api/v1/ai/search-services/"+id+"/test","POST",Map.of("query","test"),true).statusCode()).isEqualTo(429);
            assertThat(calls).hasValue(1);
            ok("/api/v1/ai/features/default-search","PUT",new AiDtos.Feature("default-search",null,null,false,3,binding.path("revision").asLong()));
            assertThat(req("/api/v1/ai/search-services/"+id,"DELETE",null,true).statusCode()).isEqualTo(204);
        } finally {fixture.stop(0);}
    }

    @Test void assistantPresentationPersistsWithRevisionAndDoesNotPauseClassification()throws Exception {
        login();var defaults=ok("/api/v1/ai/features","GET",null);
        var assistant=java.util.stream.StreamSupport.stream(defaults.spliterator(),false).filter(f->f.path("id").asText().equals("assistant")).findFirst().orElseThrow();
        assertThat(assistant.path("enabled").asBoolean()).isFalse();assertThat(assistant.path("displayMode").asText()).isEqualTo("PET");
        db.update("INSERT INTO classification_task(id,status,config_json,vocabulary_json,dimensions_json,automatic_json,created_at,updated_at,next_at) VALUES('assistant-independent','RUNNING','{}','[]','[]','[]','','','')");
        var input=new AiDtos.Feature("assistant",null,null,true,3,assistant.path("revision").asLong(),"CHAT","music-sprite");
        var saved=ok("/api/v1/ai/features/assistant","PUT",input);
        assertThat(saved.path("enabled").asBoolean()).isTrue();assertThat(settings.feature("assistant").displayMode()).isEqualTo("CHAT");
        assertThat(db.queryForObject("SELECT status FROM classification_task WHERE id='assistant-independent'",String.class)).isEqualTo("RUNNING");
        assertThat(req("/api/v1/ai/features/assistant","PUT",input,true).statusCode()).isEqualTo(409);
        for(var bad:List.of(new AiDtos.Feature("assistant",null,null,true,3,saved.path("revision").asLong(),"INVALID","music-sprite"),new AiDtos.Feature("assistant",null,null,true,3,saved.path("revision").asLong(),"PET","unknown-pet"),new AiDtos.Feature("assistant","unexpected-model",null,true,3,saved.path("revision").asLong(),"PET","music-sprite")))
            assertThat(req("/api/v1/ai/features/assistant","PUT",bad,true).statusCode()).isEqualTo(400);
        var disabled=ok("/api/v1/ai/features/assistant","PUT",new AiDtos.Feature("assistant",null,null,false,3,saved.path("revision").asLong(),"PET","music-sprite"));
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
    }

}
