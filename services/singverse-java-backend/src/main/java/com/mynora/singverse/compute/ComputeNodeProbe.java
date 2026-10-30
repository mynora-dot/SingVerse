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
            return result("AVAILABLE",capabilities.contains("media-tags")?"连接与认证成功，音视频标签读取可用":"连接与认证成功，节点暂未开放媒体能力",List.copyOf(capabilities),start);
        } catch(ResponseStatusException error) {throw error;}
        catch(InterruptedException error) {Thread.currentThread().interrupt();return result("UNAVAILABLE","连接已中断，请重试",List.of(),start);}
        catch(Exception error) {return result("UNAVAILABLE","节点连接失败或超时，请检查地址、服务及网络",List.of(),start);}
    }
    private static Check result(String status,String message,List<String> capabilities,long start) {return new Check(status,message,capabilities,(System.nanoTime()-start)/1_000_000);}
}
