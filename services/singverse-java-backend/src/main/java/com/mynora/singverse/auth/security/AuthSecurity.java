package com.mynora.singverse.auth.security;

import com.mynora.singverse.auth.crypto.AuthCrypto;

import com.mynora.singverse.auth.service.AuthService;

import com.mynora.singverse.auth.exception.AuthFailure;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.context.annotation.*;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type =
                org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
                        .SERVLET)
public class AuthSecurity {
    static void problem(HttpServletResponse response, int status, String detail, int retry)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (retry > 0) response.setHeader("Retry-After", Integer.toString(retry));
        new ObjectMapper()
                .writeValue(
                        response.getOutputStream(),
                        Map.of(
                                "type",
                                status == 401
                                        ? "urn:singverse:auth:session-required"
                                        : "about:blank",
                                "title",
                                status == 401
                                        ? "Unauthorized"
                                        : status == 429 ? "Too Many Requests" : "Request rejected",
                                "status",
                                status,
                                "detail",
                                detail));
    }

    static class CsrfRepository implements CsrfTokenRepository {
        final AuthService service;
        final AuthHttp cookies;

        CsrfRepository(AuthService service, AuthHttp cookies) {
            this.service = service;
            this.cookies = cookies;
        }

        public CsrfToken generateToken(HttpServletRequest request) {
            return new DefaultCsrfToken("X-CSRF-Token", "_csrf", AuthCrypto.token());
        }

        public void saveToken(
                CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (token == null) {
                service.revoke(cookies.cookie(request, AuthHttp.ANONYMOUS));
                cookies.set(response, AuthHttp.ANONYMOUS, null);
                return;
            }
            var issued = service.issue("anonymous");
            // Store the exact framework-generated CSRF token, bound to a server-side context.
            serviceCsrf(issued.token(), token.getToken());
            cookies.set(response, AuthHttp.ANONYMOUS, issued.token());
        }

        void serviceCsrf(String token, String csrf) {
            service.setCsrf(token, csrf);
        }

        public CsrfToken loadToken(HttpServletRequest request) {
            for (var pair :
                    List.of(
                            new String[] {AuthHttp.SESSION, "session"},
                            new String[] {AuthHttp.CHALLENGE, "challenge"},
                            new String[] {AuthHttp.ANONYMOUS, "anonymous"})) {
                var row = service.lookup(cookies.cookie(request, pair[0]), pair[1]);
                if (row != null)
                    return new DefaultCsrfToken(
                            "X-CSRF-Token", "_csrf", row.get("csrf").toString());
            }
            return null;
        }
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService service, AuthHttp cookies)
            throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        http.csrf(
                csrf ->
                        csrf.csrfTokenRepository(new CsrfRepository(service, cookies))
                                .csrfTokenRequestHandler(csrfHandler));
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.securityContext(s -> s.securityContextRepository(new NullSecurityContextRepository()));
        http.requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable());
        http.authorizeHttpRequests(
                a ->
                        a.dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)
                                .permitAll()
                                .requestMatchers(
                                        "/api/health",
                                        "/api/v1/auth/csrf",
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/mfa/verify")
                                .permitAll()
                                .requestMatchers("/api/**")
                                .authenticated()
                                .anyRequest()
                                .permitAll());
        http.exceptionHandling(
                e ->
                        e.authenticationEntryPoint(
                                        (req, res, error) -> problem(res, 401, "请先登录", 0))
                                .accessDeniedHandler(
                                        (req, res, error) -> problem(res, 403, "请求未通过安全校验", 0)));
        http.addFilterAfter(
                new OncePerRequestFilter() {
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws ServletException, IOException {
                        try {
                            cookies.origin(request);
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.setHeader("Cache-Control", "no-store");
                                if (service.authenticated(
                                        cookies.cookie(request, AuthHttp.SESSION)))
                                    SecurityContextHolder.getContext()
                                            .setAuthentication(
                                                    new UsernamePasswordAuthenticationToken(
                                                            "admin",
                                                            null,
                                                            List.of(
                                                                    new SimpleGrantedAuthority(
                                                                            "ADMIN"))));
                            }
                            chain.doFilter(request, response);
                        } catch (AuthFailure failure) {
                            problem(
                                    response,
                                    failure.status,
                                    failure.getMessage(),
                                    failure.retryAfter);
                        }
                    }
                },
                SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(AuthFailure.class)
        ResponseEntity<ProblemDetail> handle(AuthFailure error) {
            var problem =
                    ProblemDetail.forStatusAndDetail(
                            org.springframework.http.HttpStatusCode.valueOf(error.status),
                            error.getMessage());
            var response = ResponseEntity.status(error.status).header("Cache-Control", "no-store");
            if (error.retryAfter > 0)
                response.header("Retry-After", Integer.toString(error.retryAfter));
            return response.body(problem);
        }
    }
}
