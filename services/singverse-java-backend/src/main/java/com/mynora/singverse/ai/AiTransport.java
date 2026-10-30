package com.mynora.singverse.ai;

import com.fasterxml.jackson.databind.*;
import com.mynora.singverse.media.metadata.BoundedResponse;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.mynora.singverse.media.metadata.MetadataJson.bad;

@Component
public class AiTransport {
    public static class Failure extends RuntimeException {
        private final int status;
        public Failure(String message,int status) { super(message); this.status=status; }
        public int status() { return status; }
    }
    private final ObjectMapper json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AiTransport(ObjectMapper json) { this.json=json; }
    public static URI endpoint(String value) {
        try {
            if(value==null || value.length()>2048 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            URI uri=URI.create(value);
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("http","https").contains(uri.getScheme())) throw new IllegalArgumentException();
            if(uri.getScheme().equals("http")) for(var address:InetAddress.getAllByName(uri.getHost()))
                if(!address.isLoopbackAddress() && !address.isSiteLocalAddress()) throw new IllegalArgumentException();
            return uri;
        } catch(Exception e) { throw bad("服务地址无效；远程使用 HTTPS，HTTP 仅允许本机或局域网，地址不能包含凭据、查询或片段"); }
    }
    public JsonNode send(String endpoint,String query,Map<String,String> headers,Object body,int timeoutSeconds) {
        var uri=endpoint(endpoint);
        try {
            if(query!=null) uri=URI.create(uri.toASCIIString()+"?"+query);
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).header("Accept","application/json");
            headers.forEach(request::header);
            if(body==null)request.GET();
            else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=client.send(request.build(),info->new BoundedResponse(128_000));
            int status=response.statusCode();
            if(status==401 || status==403)throw new Failure("服务鉴权失败（HTTP "+status+"），请检查密钥及权限",status);
            if(status==429)throw new Failure("服务限流（HTTP 429），至少冷却 60 秒后重试",status);
            if(status<200 || status>=300)throw new Failure("服务请求失败（HTTP "+status+"），请检查协议、地址及 JSON Schema / JSON 模式支持",status);
            return json.readTree(response.body());
        } catch(Failure e) {throw e;}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Failure("服务请求已中断",502);}
        catch(HttpTimeoutException e) {throw new Failure("服务请求超时，请稍后重试",504);}
        catch(Exception e) {throw new Failure("服务连接或响应格式无效，请检查网络、协议及响应大小",502);}
    }
}
