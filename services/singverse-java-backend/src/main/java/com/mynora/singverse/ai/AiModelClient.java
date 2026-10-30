package com.mynora.singverse.ai;

import com.fasterxml.jackson.databind.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.mynora.singverse.ai.AiDtos.*;

/** Protocol adaptation only: business schemas and result validation belong to callers. */
@Component
public class AiModelClient {
    private final AiTransport transport;
    private final ObjectMapper json;
    public AiModelClient(AiTransport transport,ObjectMapper json) { this.transport=transport; this.json=json; }
    public String generate(Snapshot config,String key,String instructions,String input,Object schema) {
        Map<String,Object> body=new LinkedHashMap<>();Map<String,String> headers=new HashMap<>();
        String endpoint=config.endpoint();String protocol=config.protocol();boolean structured=schema!=null;
        boolean strict="JSON_SCHEMA".equals(config.outputMode());
        if(structured && !strict)instructions+="\n返回一个 JSON 对象，不要 Markdown；必须满足以下 JSON Schema："+schemaText(schema);
        switch(protocol) {
            case "CHAT_COMPLETIONS" -> {
                body.put("model",config.model());body.put("messages",List.of(Map.of("role","system","content",instructions),Map.of("role","user","content",input)));body.put("max_tokens",1800);
                if(structured)body.put("response_format",strict?Map.of("type","json_schema","json_schema",Map.of("name","song_classification","strict",true,"schema",schema)):Map.of("type","json_object"));
                bearer(headers,key);
            }
            case "RESPONSES" -> {
                body.put("model",config.model());body.put("instructions",instructions);body.put("input",input);body.put("max_output_tokens",1800);
                if(structured)body.put("text",Map.of("format",strict?Map.of("type","json_schema","name","song_classification","strict",true,"schema",schema):Map.of("type","json_object")));
                bearer(headers,key);
            }
            case "ANTHROPIC" -> {
                body.put("model",config.model());body.put("system",instructions);body.put("messages",List.of(Map.of("role","user","content",input)));body.put("max_tokens",1800);
                if(structured)body.put("output_config",Map.of("format",Map.of("type","json_schema","schema",anthropicSchema(json.valueToTree(schema)))));
                headers.put("anthropic-version","2023-06-01");if(key!=null)headers.put("x-api-key",key);
            }
            case "GEMINI" -> {
                endpoint=endpoint.replaceAll("/+$","")+"/models/"+URLEncoder.encode(config.model(),StandardCharsets.UTF_8).replace("+","%20")+":generateContent";
                body.put("systemInstruction",Map.of("parts",List.of(Map.of("text",instructions))));body.put("contents",List.of(Map.of("role","user","parts",List.of(Map.of("text",input)))));
                Map<String,Object> generation=new LinkedHashMap<>();generation.put("maxOutputTokens",1800);
                if(structured){generation.put("responseMimeType","application/json");if(strict)generation.put("responseJsonSchema",schema);}
                body.put("generationConfig",generation);if(key!=null)headers.put("x-goog-api-key",key);
            }
            default -> throw new AiTransport.Failure("不支持的模型协议",400);
        }
        JsonNode root=transport.send(endpoint,null,headers,body,config.timeoutSeconds());
        String text="";
        switch(protocol) {
            case "CHAT_COMPLETIONS" -> {
                var choice=root.path("choices").path(0);var message=choice.path("message");
                if(!choice.path("finish_reason").asText().equals("stop") || !message.path("refusal").asText("").isEmpty())throw incomplete();
                text=message.path("content").asText("");
            }
            case "RESPONSES" -> {
                if(!root.path("status").asText().equals("completed"))throw incomplete();
                for(var output:root.path("output"))for(var part:output.path("content")) {
                    if(part.path("type").asText().equals("refusal"))throw incomplete();
                    if(part.path("type").asText().equals("output_text"))text+=part.path("text").asText("");
                }
            }
            case "ANTHROPIC" -> {
                if(!root.path("stop_reason").asText().equals("end_turn"))throw incomplete();
                for(var part:root.path("content"))if(part.path("type").asText().equals("text"))text+=part.path("text").asText("");
            }
            case "GEMINI" -> {
                var candidate=root.path("candidates").path(0);if(!candidate.path("finishReason").asText().equals("STOP"))throw incomplete();
                for(var part:candidate.path("content").path("parts"))if(!part.path("thought").asBoolean(false))text+=part.path("text").asText("");
            }
        }
        if(text.isBlank())throw incomplete();
        return text;
    }
    // Anthropic's native schema accepts array shape but not maxItems. Keep these
    // limits in descriptions and in the caller's result validator; remain in Schema mode.
    private JsonNode anthropicSchema(JsonNode node) {
        if(node.isArray()) {var result=json.createArrayNode();node.forEach(child->result.add(anthropicSchema(child)));return result;}
        if(!node.isObject())return node.deepCopy();
        var result=json.createObjectNode();
        node.fields().forEachRemaining(entry->{
            if(entry.getKey().equals("properties")) {
                var properties=json.createObjectNode();entry.getValue().fields().forEachRemaining(property->properties.set(property.getKey(),anthropicSchema(property.getValue())));result.set("properties",properties);
            } else if(!entry.getKey().equals("maxItems"))result.set(entry.getKey(),anthropicSchema(entry.getValue()));
        });
        if(node.has("maxItems"))result.put("description",node.path("description").asText("")+" 最多 "+node.path("maxItems").asInt()+" 项；结果由应用再次校验。");
        return result;
    }
    private String schemaText(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid schema",e);}}
    private static void bearer(Map<String,String> headers,String key) {if(key!=null)headers.put("Authorization","Bearer "+key);}
    private static AiTransport.Failure incomplete() {return new AiTransport.Failure("模型拒绝回答、返回空内容或输出未完成，未应用任何分类",502);}
}
