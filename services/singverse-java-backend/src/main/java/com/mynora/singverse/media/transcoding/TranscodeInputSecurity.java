package com.mynora.singverse.media.transcoding;

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
public class TranscodeInputSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain computeInputChain(HttpSecurity http) throws Exception {
        // GET/HEAD only; controller validates node bearer AND task-scoped media authorization.
        return http.securityMatcher("/api/v1/internal/transcode-input/**", "/api/v1/internal/metadata-input/**", "/api/v1/internal/audio-input/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
