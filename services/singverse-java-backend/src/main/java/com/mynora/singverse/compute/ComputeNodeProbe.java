package com.mynora.singverse.compute;

import static com.mynora.singverse.compute.ComputeNodeDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.media.metadata.BoundedResponse;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ComputeNodeProbe {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ComputeCallback callback;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ComputeNodeProbe(ObjectMapper json) {this.json=json;}
    public Check check(Connection connection) {
        long start=System.nanoTime();
        if(connection==null) return result("DISABLED","未启用节点，或没有发现部署配置",List.of(),start);
        if(connection.token().isBlank())return result("MISSING_TOKEN","已发现节点地址，尚未配置可用的认证凭据",List.of(),start);
        try {
            ComputeNodeSettings.checkNetwork(connection.origin());
            var request=HttpRequest.newBuilder(connection.origin().resolve("/internal/v1/status")).timeout(Duration.ofSeconds(4))
                    .header("Authorization","Bearer "+connection.token()).header("Accept","application/json").GET().build();
            var response=http.send(request,info->new BoundedResponse(65536));
            if(response.statusCode()==401 || response.statusCode()==403)return result("AUTH_FAILED","连接到了节点，但认证凭据不匹配",List.of(),start);
            if(response.statusCode()!=200)return result("UNAVAILABLE","节点未就绪或版本不支持认证状态检查",List.of(),start);
            var body=json.readTree(response.body());
            if(body==null || !"singverse-compute".equals(body.path("service").asText()) || !"1".equals(body.path("apiVersion").asText()) || !body.path("capabilities").isArray())
                return result("INCOMPATIBLE","地址可以访问，但不是兼容的SingVerse计算服务",List.of(),start);
            var capabilities=new ArrayList<String>();
            for(var capability:body.path("capabilities")) {
                if(!capability.isTextual() || capability.asText().isBlank() || capability.asText().length()>100 || capabilities.size()>=32)
                    return result("INCOMPATIBLE","节点能力响应无效",List.of(),start);
                capabilities.add(capability.asText());
            }
            if (callback != null) {
                String reference = callback.authorize(connection.token());
                try {
                    var payload = json.createObjectNode().put("reference", reference).put("callbackOrigin", callback.origin());
                    var check = HttpRequest.newBuilder(connection.origin().resolve("/internal/v1/callback/check"))
                            .timeout(Duration.ofSeconds(7)).header("Authorization", "Bearer " + connection.token())
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
                    var back = http.send(check, info -> new BoundedResponse(4096));
                    if (back.statusCode() != 200 || !json.readTree(back.body()).path("available").asBoolean())
                        return result("CALLBACK_FAILED", "节点连接与认证成功，但业务回源不可用；请使用节点可达的业务地址打开管理页，并检查反向代理与网络", capabilities, start);
                } catch (Exception error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    return result("CALLBACK_FAILED", "节点连接与认证成功，但无法确定或访问业务回源地址", capabilities, start);
                } finally { callback.revoke(reference); }
            }
            return result("AVAILABLE",(capabilities.contains("media-tags")||capabilities.contains("media-remote-tags"))?"节点连接、认证与业务回源成功，媒体读取可用":"连接与认证成功，节点暂未开放媒体能力",List.copyOf(capabilities),start);
        } catch(ResponseStatusException error) {throw error;}
        catch(InterruptedException error) {Thread.currentThread().interrupt();return result("UNAVAILABLE","连接已中断，请重试",List.of(),start);}
        catch(Exception error) {return result("UNAVAILABLE","节点连接失败或超时，请检查地址、服务及网络",List.of(),start);}
    }
    private static Check result(String status,String message,List<String> capabilities,long start) {return new Check(status,message,capabilities,(System.nanoTime()-start)/1_000_000);}
}
