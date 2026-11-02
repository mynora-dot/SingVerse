package com.mynora.singverse.compute;

import com.mynora.singverse.auth.security.AuthHttp;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.bind.annotation.*;

/** Only authenticated administrator requests may establish the fallback business origin. */
@Component
public class ComputeCallback implements WebMvcConfigurer, HandlerInterceptor {
    private final AuthHttp auth;
    private final String configured;
    private volatile String observed = "";
    private final Map<String, Grant> checks = new ConcurrentHashMap<>();
    private record Grant(String token, long expires) {}
    public ComputeCallback(AuthHttp auth, Environment env) {
        this.auth = auth;
        String value = env.getProperty("singverse.compute.callback-origin", "");
        if (value.isBlank()) value = env.getProperty("singverse.auth.public-origin", "");
        configured = value.isBlank() ? "" : ComputeNodeSettings.origin(value).toString();
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/internal/**");
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var identity = SecurityContextHolder.getContext().getAuthentication();
        if (configured.isEmpty() && identity != null && identity.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ADMIN"))) {
            observed = ComputeNodeSettings.origin(auth.callbackOrigin(request)).toString();
        }
        return true;
    }
    public String origin() {
        String value = configured.isEmpty() ? observed : configured;
        if (value.isBlank()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "无法确定业务回源地址，请通过计算节点可达的地址打开管理页后测试连接");
        return value;
    }
    public String authorize(String token) {
        checks.entrySet().removeIf(e -> e.getValue().expires < System.nanoTime());
        String id = UUID.randomUUID().toString();
        checks.put(id, new Grant(token, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)));
        return id;
    }
    public void revoke(String id) { checks.remove(id); }
    public String verify(String id, String authorization) {
        var grant = checks.get(id);
        if (grant == null || System.nanoTime() > grant.expires || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + grant.token).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "业务回源认证失败");
        return id;
    }
}
