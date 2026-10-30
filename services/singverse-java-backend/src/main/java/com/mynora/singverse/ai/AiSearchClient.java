package com.mynora.singverse.ai;

import static com.mynora.singverse.ai.AiDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiSearchClient {
    private final AiTransport transport;
    public AiSearchClient(AiTransport transport) {this.transport=transport;}
    public List<SearchHit> search(Snapshot config,String key,String query) {
        Map<String,String> headers=new HashMap<>();Object body=null;String parameters=null;
        switch(config.protocol()) {
            case "TAVILY" -> {bearer(headers,key);body=Map.of("query",query,"max_results",config.maxResults(),"include_answer",false,"include_raw_content",false);}
            case "EXA" -> {if(key!=null)headers.put("x-api-key",key);body=Map.of("query",query,"numResults",config.maxResults(),"contents",Map.of("highlights",true));}
            case "BRAVE" -> {if(key!=null)headers.put("X-Subscription-Token",key);parameters="q="+encode(query)+"&count="+config.maxResults();}
            case "BOCHA" -> {bearer(headers,key);body=Map.of("query",query,"count",config.maxResults(),"summary",false);}
            case "SEARXNG" -> {bearer(headers,key);parameters="q="+encode(query)+"&format=json";}
            case "CUSTOM-SEARCH" -> {bearer(headers,key);body=Map.of("query",query,"maxResults",config.maxResults());}
            default -> throw new AiTransport.Failure("不支持的搜索协议",400);
        }
        JsonNode root=transport.send(config.endpoint(),parameters,headers,body,config.timeoutSeconds());
        JsonNode items=switch(config.protocol()) {
            case "BRAVE" -> root.path("web").path("results");
            case "BOCHA" -> root.path("data").path("webPages").path("value");
            default -> root.path("results");
        };
        if(!items.isArray())throw new AiTransport.Failure("搜索响应结构无效，请检查服务协议；SearXNG 需启用 JSON 输出",502);
        List<SearchHit> results=new ArrayList<>();
        for(var item:items) {
            String url=item.path("url").asText("");
            try {URI parsed=URI.create(url);if(parsed.getHost()==null || parsed.getUserInfo()!=null || !Set.of("http","https").contains(parsed.getScheme()))continue;}catch(Exception e){continue;}
            String title=item.path("title").asText(item.path("name").asText("无标题"));
            String snippet=item.path("snippet").asText(item.path("description").asText(item.path("content").asText("")));
            if(config.protocol().equals("EXA") && item.path("highlights").isArray()) {var parts=new ArrayList<String>();item.path("highlights").forEach(p->parts.add(p.asText()));snippet=String.join(" ",parts);}
            results.add(new SearchHit(limit(title,300),limit(url,2048),limit(snippet,2000)));
            if(results.size()>=config.maxResults())break;
        }
        return results;
    }
    private static String limit(String value,int max){return value.length()<=max?value:value.substring(0,max);}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static void bearer(Map<String,String> headers,String key){if(key!=null)headers.put("Authorization","Bearer "+key);}
}
