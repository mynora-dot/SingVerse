package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("sqlite")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ClassificationHttpTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("singverse.sqlite.path",()->temp.resolve("classification.db").toString());
        p.add("singverse.auth.key-file",()->temp.resolve("auth.key").toString());
        p.add("singverse.classification.worker-enabled",()->false);
        p.add("singverse.metadata.worker-enabled",()->false);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ClassificationWorker worker;
    @MockitoSpyBean ClassificationModel model;
    HttpClient client;
    static final String BASE="/api/v1/classification";
    @BeforeEach void reset() {
        for(String table:List.of("classification_receipt","classification_history","classification_item","classification_task","classification_assignment","classification_song","media_song","media_source"))db.update("DELETE FROM "+table);
        db.update("UPDATE classification_settings SET enabled=0,endpoint='',model='',secret_encrypted=NULL,next_request_at='' WHERE id=1");
        db.update("UPDATE classification_tag SET active=1,revision=1");
        client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        doReturn(suggestion("genre-0")).when(model).generate(any(),any(),anyList(),anySet());
    }
    Suggestion suggestion(String tag) {return new Suggestion(Map.of("GENRE",List.of(tag)),Map.of("GENRE","根据歌曲信息推断"),List.of());}
    HttpResponse<String> req(String path,String method,Object data,boolean csrf) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf)b.header("X-CSRF-Token",body(req("/api/v1/auth/csrf","GET",null,false)).path("token").asText());
        if(data!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode body(HttpResponse<String> r)throws Exception{return json.readTree(r.body());}
    JsonNode ok(String path,String method,Object input)throws Exception{var r=req(path,method,input,true);assertThat(r.statusCode()).withFailMessage(r.body()).isBetween(200,201);return body(r);}
    void login()throws Exception{ok("/api/v1/auth/login","POST",Map.of("username","admin","password","admin"));}
    void settings()throws Exception{ok(BASE+"/settings","PUT",Map.of("enabled",true,"endpoint","http://127.0.0.1:9999/v1/chat/completions","model","test-model","apiKey","fixture-key","timeoutSeconds",10,"intervalSeconds",2));}
    String song(String title)throws Exception{
        Path dir=Files.createTempDirectory(temp,"songs");Files.writeString(dir.resolve("歌手《"+title+"》-国语.mkv"),"test");
        String id=ok("/api/v1/media-sources","POST",Map.of("name",title,"type","LOCAL","location",dir.toString())).path("id").asText();
        ok("/api/v1/media-sources/"+id+"/scan","POST",null);
        return ok("/api/v1/media-sources/"+id+"/songs","GET",null).at("/items/0/id").asText();
    }
    String start(List<String> ids,boolean auto)throws Exception{return ok(BASE+"/tasks","POST",new Start(false,ids,Set.of("GENRE"),auto?Set.of("GENRE"):Set.of())).path("id").asText();}
    JsonNode task(String id)throws Exception{return ok(BASE+"/tasks/"+id,"GET",null);}
    JsonNode state(String id)throws Exception{return ok(BASE+"/songs/"+id,"GET",null);}
    void tick(){db.update("UPDATE classification_settings SET next_request_at=''");db.update("UPDATE classification_task SET next_at=''");worker.processNext();}
    Change change(long revision,String tag,boolean locked){return new Change(UUID.randomUUID().toString(),revision,Map.of("GENRE",List.of(tag)),locked?Set.of("GENRE"):Set.of());}
    @Test void authenticationDefaultsSecretsAndPreview()throws Exception{
        assertThat(req(BASE+"/settings","GET",null,false).statusCode()).isEqualTo(401);login();
        assertThat(stateMissingSettings().path("enabled").asBoolean()).isFalse();
        assertThat(req(BASE+"/settings","PUT",Map.of(),false).statusCode()).isEqualTo(403);
        settings();assertThat(stateMissingSettings().toString()).doesNotContain("fixture-key","encryptedKey");
        assertThat(db.queryForObject("SELECT secret_encrypted FROM classification_settings",String.class)).doesNotContain("fixture-key");
        String song=song("晴天");var preview=ok(BASE+"/preview","POST",new Start(false,List.of(song),Set.of("GENRE"),Set.of()));
        assertThat(preview.toString()).doesNotContain("relativePath",temp.toString(),"fixture-key");assertThat(preview.path("total").asInt()).isEqualTo(1);
        verify(model,never()).generate(any(),any(),anyList(),anySet());
    }
    JsonNode stateMissingSettings()throws Exception{return ok(BASE+"/settings","GET",null);}
    @Test void manualTagsAreIdempotentIndexedAndUndoProtectsLaterEdits()throws Exception{
        login();String a=song("晴天"),b=song("后来");var input=change(0,"genre-0",true);
        assertThat(ok(BASE+"/songs/"+a,"PUT",input).path("revision").asInt()).isEqualTo(1);
        assertThat(ok(BASE+"/songs/"+a,"PUT",input).path("revision").asInt()).isEqualTo(1);
        assertThat(req(BASE+"/songs/"+a,"PUT",change(0,"genre-1",false),true).statusCode()).isEqualTo(409);
        var filtered=ok("/api/v1/media-songs?tagIds=genre-0&tagIds=genre-1","GET",null);assertThat(filtered.path("totalElements").asInt()).isEqualTo(1);
        assertThat(ok("/api/v1/media-songs?unclassified=true","GET",null).path("totalElements").asInt()).isEqualTo(1);
        assertThat(ok("/api/v1/media-songs?tagIds=genre-0&tagIds=mood-0","GET",null).path("totalElements").asInt()).isZero();
        String history=ok(BASE+"/songs/"+a+"/history","GET",null).get(0).path("id").asText();
        ok(BASE+"/songs/"+a,"PUT",change(1,"genre-1",false));
        assertThat(req(BASE+"/history/"+history+"/undo","POST",new Undo(UUID.randomUUID().toString(),2),true).statusCode()).isEqualTo(409);
        history=ok(BASE+"/songs/"+a+"/history","GET",null).get(0).path("id").asText();
        ok(BASE+"/history/"+history+"/undo","POST",new Undo(UUID.randomUUID().toString(),2));
        assertThat(state(a).at("/dimensions/GENRE/tagIds/0").asText()).isEqualTo("genre-0");
        assertThat(state(b).path("revision").asInt()).isZero();
    }
    @Test void automaticDefaultsOffAndReviewRequiresExplicitApply()throws Exception{
        login();settings();String song=song("晴天"),task=start(List.of(song),false);tick();var result=task(task);
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");assertThat(result.path("completed").asInt()).isEqualTo(1);
        assertThat(result.at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(state(song).path("revision").asInt()).isZero();
        String item=result.at("/items/0/id").asText();var review=new Review(UUID.randomUUID().toString(),0,Set.of("GENRE"));
        ok(BASE+"/tasks/"+task+"/items/"+item+"/apply","POST",review);ok(BASE+"/tasks/"+task+"/items/"+item+"/apply","POST",review);
        assertThat(state(song).at("/dimensions/GENRE/origin").asText()).isEqualTo("AI_REVIEWED");assertThat(task(task).at("/counts/APPLIED").asInt()).isEqualTo(1);
    }
    @Test void automaticInferenceIsAppliedButManualAndLockedValuesArePreserved()throws Exception{
        login();settings();String song=song("晴天"),task=start(List.of(song),true);tick();assertThat(state(song).at("/dimensions/GENRE/origin").asText()).isEqualTo("AI");
        ok(BASE+"/songs/"+song,"PUT",change(1,"genre-1",true));task=start(List.of(song),true);tick();
        assertThat(task(task).at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(state(song).at("/dimensions/GENRE/tagIds/0").asText()).isEqualTo("genre-1");
        assertThat(req(BASE+"/tasks/"+task+"/items/"+task(task).at("/items/0/id").asText()+"/apply","POST",new Review(UUID.randomUUID().toString(),2,Set.of("GENRE")),true).statusCode()).isEqualTo(409);
    }
    @Test void unknownOrCrossDimensionOutputNeverWrites()throws Exception{
        login();settings();String song=song("晴天");doReturn(suggestion("mood-0")).when(model).generate(any(),any(),anyList(),anySet());
        String task=start(List.of(song),true);tick();assertThat(task(task).at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(state(song).path("revision").asInt()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM classification_history",Integer.class)).isZero();
    }
    @Test void emptySuggestionsDoNotEraseExistingAiTags()throws Exception{
        login();settings();String song=song("晴天");start(List.of(song),true);tick();
        doReturn(new Suggestion(Map.of("GENRE",List.of()),Map.of("GENRE","未知"),List.of())).when(model).generate(any(),any(),anyList(),anySet());
        String task=start(List.of(song),true);tick();assertThat(task(task).at("/counts/UNCHANGED").asInt()).isEqualTo(1);assertThat(state(song).at("/dimensions/GENRE/tagIds/0").asText()).isEqualTo("genre-0");
    }
    @Test void pauseResumeCancelRecoveryAndCountsAreConsistent()throws Exception{
        login();settings();String a=song("晴天"),b=song("后来"),task=start(List.of(a,b),false);
        ok(BASE+"/tasks/"+task+"/pause","POST",null);tick();assertThat(task(task).path("completed").asInt()).isZero();
        ok(BASE+"/tasks/"+task+"/resume","POST",null);tick();assertThat(task(task).path("completed").asInt()).isEqualTo(1);
        worker.recover();assertThat(task(task).path("status").asText()).isEqualTo("PAUSED");
        ok(BASE+"/tasks/"+task+"/cancel","POST",null);var result=task(task);assertThat(result.path("completed").asInt()).isEqualTo(2);assertThat(result.at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(result.at("/counts/CANCELLED").asInt()).isEqualTo(1);
        assertThat(req(BASE+"/tasks/"+task+"/resume","POST",null,true).statusCode()).isEqualTo(409);
    }
    @Test void retryDoesNotApplyWhenAutomaticIsOffAndRateLimitPersistsCooldown()throws Exception{
        login();settings();String song=song("晴天");doThrow(new ClassificationModel.ModelFailure("HTTP 429 冷却",true)).when(model).generate(any(),any(),anyList(),anySet());
        String task=start(List.of(song),false);tick();var result=task(task);assertThat(result.at("/counts/FAILED").asInt()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT next_request_at FROM classification_settings",String.class)).isGreaterThan(java.time.Instant.now().plusSeconds(55).toString());
        doReturn(suggestion("genre-0")).when(model).generate(any(),any(),anyList(),anySet());ok(BASE+"/tasks/"+task+"/items/"+result.at("/items/0/id").asText()+"/retry","POST",null);
        worker.processNext();assertThat(task(task).at("/counts/PENDING").asInt()).isEqualTo(1);tick();assertThat(state(song).path("revision").asInt()).isZero();
    }
    @Test void changedSongInputOrVocabularyBlocksAutomaticWrites()throws Exception{
        login();settings();String song=song("晴天"),task=start(List.of(song),true);
        db.update("UPDATE media_song SET file_size=file_size+1 WHERE id=?",song);tick();assertThat(task(task).at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(state(song).path("revision").asInt()).isZero();
        task=start(List.of(song),true);db.update("UPDATE classification_tag SET revision=revision+1 WHERE id='genre-0'");tick();assertThat(task(task).at("/counts/REVIEW").asInt()).isEqualTo(1);assertThat(state(song).path("revision").asInt()).isZero();
    }
    @Test void savingSettingsPausesAndDoesNotExposeFrozenCredentials()throws Exception{
        login();settings();String song=song("晴天"),task=start(List.of(song),true);settings();
        assertThat(task(task).path("status").asText()).isEqualTo("PAUSED");assertThat(task(task).toString()).doesNotContain("fixture-key","encryptedKey","config_json");
    }
    @Test void inFlightManualEditPreventsAutomaticOverwrite()throws Exception{
        login();settings();String song=song("晴天"),task=start(List.of(song),true);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return suggestion("genre-0");}).when(model).generate(any(),any(),anyList(),anySet());
        var future=CompletableFuture.runAsync(worker::processNext);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        try{ok(BASE+"/songs/"+song,"PUT",change(0,"genre-1",false));}finally{release.countDown();}future.get(10,TimeUnit.SECONDS);
        assertThat(state(song).at("/dimensions/GENRE/tagIds/0").asText()).isEqualTo("genre-1");assertThat(task(task).at("/counts/REVIEW").asInt()).isEqualTo(1);
    }
}
