package com.mynora.singverse.media.classification;

import static com.mynora.singverse.media.classification.ClassificationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.metadata.MetadataJson;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

class ClassificationModelTest {
    HttpServer server;String response;int code=200;
    ObjectMapper json=new ObjectMapper();AtomicReference<String> received=new AtomicReference<>();
    ClassificationModel model;ModelConfig config;
    Input input=new Input("song-id","歌曲",List.of("歌手"),null,null,"UNKNOWN","UNKNOWN",List.of());
    List<Tag> vocabulary=List.of(new Tag("genre-0","GENRE","流行",List.of(),true,1,0));
    @BeforeEach void start()throws Exception {
        var settings=mock(ClassificationSettings.class);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        config=new ModelConfig(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions","fixture",null,5,2,1);
        model=new ClassificationModel(json,new MetadataJson(json),settings);
        response=wrap(Map.of("dimensions",Map.of("GENRE",List.of("genre-0")),"reasons",Map.of("GENRE","推断"),"newTagSuggestions",List.of()));
    }
    @AfterEach void stop(){server.stop(0);}
    String wrap(Object content)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(content))))));}
    @Test void sendsMinimalInputAndStrictSchema()throws Exception{
        assertThat(model.generate(config,input,vocabulary,Set.of("GENRE")).dimensions().get("GENRE")).containsExactly("genre-0");
        var body=json.readTree(received.get());assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(received.get()).doesNotContain("relativePath","apiKey","encryptedKey","sourceName");
    }
    @Test void unknownAndDuplicateLabelsAreRejected()throws Exception{
        for(List<String> ids:List.of(List.of("invented"),List.of("genre-0","genre-0"))) {
            response=wrap(Map.of("dimensions",Map.of("GENRE",ids),"reasons",Map.of("GENRE","推断"),"newTagSuggestions",List.of()));
            assertThatThrownBy(()->model.generate(config,input,vocabulary,Set.of("GENRE"))).isInstanceOf(ClassificationModel.ModelFailure.class);
        }
    }
    @Test void rateLimitAndIncompatibleProtocolAreExplicit() {
        code=429;response="upstream secret should never appear";
        assertThatThrownBy(()->model.generate(config,input,vocabulary,Set.of("GENRE"))).isInstanceOfSatisfying(ClassificationModel.ModelFailure.class,e->{assertThat(e.rateLimited()).isTrue();assertThat(e.getMessage()).doesNotContain("secret");});
        code=400;
        assertThatThrownBy(()->model.generate(config,input,vocabulary,Set.of("GENRE"))).hasMessageContaining("JSON Schema");
    }
    @Test void oversizedAndMalformedResponsesAreRejected() {
        response="x".repeat(130000);assertThatThrownBy(()->model.generate(config,input,vocabulary,Set.of("GENRE"))).isInstanceOf(ClassificationModel.ModelFailure.class);
        response="not json";assertThatThrownBy(()->model.generate(config,input,vocabulary,Set.of("GENRE"))).isInstanceOf(ClassificationModel.ModelFailure.class);
    }
    @Test void derivedFactsOverrideInferenceAndMissingDateStaysUnknown() {
        var result=new Suggestion(Map.of("LANGUAGE",List.of("wrong"),"DECADE",List.of("guessed")),Map.of("LANGUAGE","猜测","DECADE","猜测"),List.of());
        var tags=List.of(new Tag("language-0","LANGUAGE","国语",List.of(),true,1,0),new Tag("decade-5","DECADE","2000 年代",List.of(),true,1,0));
        var current=new Input("id","歌名",List.of("歌手"),null,null,"MANDARIN","UNKNOWN",List.of());
        var derived=ClassificationWorker.facts(result,current,tags,Set.of("LANGUAGE","DECADE"));
        assertThat(derived.dimensions().get("LANGUAGE")).containsExactly("language-0");assertThat(derived.dimensions().get("DECADE")).isEmpty();
        current=new Input("id","歌名",List.of("歌手"),null,"2003-01-01","MANDARIN","UNKNOWN",List.of());
        assertThat(ClassificationWorker.facts(result,current,tags,Set.of("LANGUAGE","DECADE")).dimensions().get("DECADE")).containsExactly("decade-5");
    }
}
