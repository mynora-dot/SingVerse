package com.mynora.singverse.ai;

import static org.assertj.core.api.Assertions.*;
import static com.mynora.singverse.ai.AiDtos.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiProtocolTest {
    final ObjectMapper json=new ObjectMapper();
    HttpServer server;String response="{}";int status=200;String endpoint;volatile long delayMillis;
    final AtomicReference<JsonNode> received=new AtomicReference<>();final AtomicReference<String> query=new AtomicReference<>();final AtomicReference<com.sun.net.httpserver.Headers> headers=new AtomicReference<>();
    AiModelClient model;AiSearchClient search;
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{if(delayMillis>0)try{Thread.sleep(delayMillis);}catch(InterruptedException e){Thread.currentThread().interrupt();}headers.set(exchange.getRequestHeaders());query.set(exchange.getRequestURI().getRawQuery());String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);received.set(body.isEmpty()?json.createObjectNode():json.readTree(body));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
        endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/api";
        var transport=new AiTransport(json);model=new AiModelClient(transport,json);search=new AiSearchClient(transport);
    }
    @AfterEach void stop(){server.stop(0);}
    Snapshot config(String protocol,String mode){return new Snapshot("test",protocol,endpoint,"fixture-model",mode,null,"test",true,5,2);}
    @ParameterizedTest @ValueSource(strings={"CHAT_COMPLETIONS","RESPONSES","ANTHROPIC","GEMINI"})
    void nativeProtocolsEncodeSchemaAuthenticateAndExtractText(String protocol)throws Exception {
        response=switch(protocol){
            case "CHAT_COMPLETIONS" -> "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}";
            case "RESPONSES" -> "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"ok\\\":true}\"}]}]}";
            case "ANTHROPIC" -> "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]}";
            default -> "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}";
        };
        var schema=Map.of("type","object","properties",Map.of("ok",Map.of("type","boolean")),"required",List.of("ok"),"additionalProperties",false);
        assertThat(json.readTree(model.generate(config(protocol,"JSON_SCHEMA"),"fixture-key","instructions","input",schema)).path("ok").asBoolean()).isTrue();
        String path=switch(protocol){case "CHAT_COMPLETIONS"->"/response_format/json_schema/schema";case "RESPONSES"->"/text/format/schema";case "ANTHROPIC"->"/output_config/format/schema";default->"/generationConfig/responseJsonSchema";};
        assertThat(received.get().at(path).path("type").asText()).isEqualTo("object");
        String header=protocol.equals("GEMINI")?"x-goog-api-key":protocol.equals("ANTHROPIC")?"x-api-key":"Authorization";
        assertThat(headers.get().getFirst(header)).contains("fixture-key");
    }
    @Test void anthropicNativeSchemaPreservesShapeAndMovesArrayLimitToDescription() {
        response="{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}";
        var schema=Map.of("type","object","additionalProperties",false,"properties",Map.of("tags",Map.of("type","array","items",Map.of("type","string"),"maxItems",8)));
        model.generate(config("ANTHROPIC","JSON_SCHEMA"),null,"instructions","input",schema);
        var tags=received.get().at("/output_config/format/schema/properties/tags");
        assertThat(tags.has("maxItems")).isFalse();assertThat(tags.path("description").asText()).contains("8");
        assertThat(tags.path("items").path("type").asText()).isEqualTo("string");
        assertThat(received.get().at("/output_config/format/type").asText()).isEqualTo("json_schema");
    }
    @Test void jsonModeIsExplicitAndNotSilentlySchema() {
        response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
        model.generate(config("CHAT_COMPLETIONS","JSON_OBJECT"),null,"instructions","input",Map.of("type","object"));
        assertThat(received.get().at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(received.get().toString()).contains("JSON Schema");
    }
    @ParameterizedTest @ValueSource(strings={"TAVILY","EXA","BRAVE","BOCHA","SEARXNG","CUSTOM-SEARCH"})
    void searchProvidersNormalizeResponsesAndLimitResults(String protocol) {
        String item="{\"title\":\"标题\",\"name\":\"标题\",\"url\":\"https://example.test/result\",\"snippet\":\"摘要\",\"description\":\"摘要\",\"content\":\"摘要\",\"highlights\":[\"摘要\"]}";
        String items="["+item+","+item+","+item+"]";
        response=protocol.equals("BRAVE")?"{\"web\":{\"results\":"+items+"}}":protocol.equals("BOCHA")?"{\"data\":{\"webPages\":{\"value\":"+items+"}}}":"{\"results\":"+items+"}";
        var result=search.search(config(protocol,""),"fixture-key","中文 & 查询");
        assertThat(result).hasSize(2);assertThat(result.getFirst().snippet()).isEqualTo("摘要");
        if(Set.of("BRAVE","SEARXNG").contains(protocol))assertThat(query.get()).contains("%E4%B8%AD%E6%96%87", "%26");
        else assertThat(received.get().path("query").asText()).isEqualTo("中文 & 查询");
        if(protocol.equals("CUSTOM-SEARCH"))assertThat(received.get().path("maxResults").asInt()).isEqualTo(2);
    }
    @Test void errorsAreBoundedAndDoNotEchoUpstreamSecrets() {
        response="fixture-key upstream secret";
        for(int code:List.of(401,403,429,302,500)) {
            status=code;assertThatThrownBy(()->search.search(config("TAVILY",""),"fixture-key","query")).isInstanceOf(AiTransport.Failure.class).hasMessageNotContaining("fixture-key").hasMessageNotContaining("upstream");
        }
        status=200;response="x".repeat(130000);
        assertThatThrownBy(()->search.search(config("TAVILY",""),null,"query")).isInstanceOf(AiTransport.Failure.class);
        response="{\"results\":[{\"url\":\"javascript:alert(1)\"}]}";
        assertThat(search.search(config("TAVILY",""),null,"query")).isEmpty();
        response="{\"unexpected\":true}";assertThatThrownBy(()->search.search(config("TAVILY",""),null,"query")).hasMessageContaining("响应结构无效");
    }
    @Test void requestTimeoutIsReportedWithoutLeakingCredentials() {
        delayMillis=1400;
        var slow=new Snapshot("test","TAVILY",endpoint,"","",null,"test",true,1,2);
        assertThatThrownBy(()->search.search(slow,"fixture-key","query"))
                .isInstanceOfSatisfying(AiTransport.Failure.class,e->assertThat(e.status()).isEqualTo(504))
                .hasMessageContaining("超时").hasMessageNotContaining("fixture-key");
    }
    @Test void unsafeDestinationsAreRejected() {
        for(String url:List.of("file:///etc/passwd","http://user:secret@127.0.0.1/api","https://example.test/api?key=secret","https://example.test/api#fragment"))assertThatThrownBy(()->AiTransport.endpoint(url)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(AiTransport.endpoint(endpoint).getHost()).isEqualTo("127.0.0.1");
    }
}
