package com.mynora.singverse.auth.security;

import com.mynora.singverse.auth.exception.AuthFailure;

import jakarta.servlet.http.*;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

@Component
public class AuthHttp {
    public static final String SESSION = "singverse_session",
            CHALLENGE = "singverse_challenge",
            ANONYMOUS = "singverse_csrf";
    private final boolean secure;
    private final String publicOrigin;
    private final List<IpAddressMatcher> proxies;

    public AuthHttp(Environment env) {
        publicOrigin = env.getProperty("singverse.auth.public-origin", "");
        secure = !publicOrigin.isBlank();
        if (secure) {
            URI origin = URI.create(publicOrigin);
            if (!"https".equals(origin.getScheme())
                    || origin.getHost() == null
                    || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || !(origin.getPath().isEmpty()))
                throw new IllegalStateException(
                        "Public origin must be an HTTPS origin without path");
        }
        String proxyConfig = env.getProperty("singverse.auth.trusted-proxies", "");
        if (proxyConfig.contains("/0"))
            throw new IllegalStateException("Wildcard proxy trust is forbidden");
        proxies =
                Arrays.stream(env.getProperty("singverse.auth.trusted-proxies", "").split(","))
                        .filter(s -> !s.isBlank())
                        .map(String::trim)
                        .map(IpAddressMatcher::new)
                        .toList();
    }

    public String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() != null)
            for (var cookie : request.getCookies())
                if (cookie.getName().equals(name)) return cookie.getValue();
        return null;
    }

    public void set(HttpServletResponse response, String name, String token) {
        var cookie =
                ResponseCookie.from(name, token == null ? "" : token)
                        .httpOnly(true)
                        .secure(secure)
                        .sameSite("Lax")
                        .path("/");
        if (token == null) cookie.maxAge(0);
        response.addHeader("Set-Cookie", cookie.build().toString());
    }

    public String source(HttpServletRequest request) {
        if (proxies.stream().anyMatch(p -> p.matches(request.getRemoteAddr()))) {
            String value = request.getHeader("X-Real-IP");
            if (value != null && value.matches("[0-9a-fA-F:.]{3,45}")) return value;
        }
        return request.getRemoteAddr();
    }

    public void origin(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/v1/")) return;
        boolean trusted = proxies.stream().anyMatch(p -> p.matches(request.getRemoteAddr()));
        String scheme =
                trusted
                        ? Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
                                .orElse(request.getScheme())
                        : request.getScheme();
        String actual = scheme + "://" + request.getHeader("Host");
        String expected = secure ? publicOrigin : actual;
        if (secure && !expected.equals(actual)) throw new AuthFailure(400, "请求入口不符合 HTTPS 部署配置");
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.equals(expected)) throw new AuthFailure(403, "不允许跨来源请求");
    }

    public void clear(HttpServletResponse response) {
        for (String name : List.of(SESSION, CHALLENGE, ANONYMOUS)) set(response, name, null);
    }
}
