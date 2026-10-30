package com.mynora.singverse.tv;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type =
                org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type
                        .SERVLET)
public class TvSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain deviceChain(HttpSecurity http) throws Exception {
        // These isolated endpoints authenticate explicit bearer credentials in their controller.
        // They never read cookies. Existing browser/admin endpoints retain CSRF and ADMIN checks.
        http.securityMatcher(
                        "/api/v1/tv/**",
                        "/api/v1/party/**",
                        "/api/v1/device-authorizations/**",
                        "/api/v1/device-authorizations",
                        "/api/v1/server-info")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        http.headers(h -> h.cacheControl(c -> {}));
        return http.build();
    }
}
