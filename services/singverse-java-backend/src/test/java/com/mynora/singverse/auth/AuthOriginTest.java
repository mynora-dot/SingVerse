package com.mynora.singverse.auth;

import com.mynora.singverse.auth.exception.AuthFailure;

import com.mynora.singverse.auth.security.AuthHttp;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class AuthOriginTest {
    MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
        request.setRemoteAddr("127.0.0.1");
        request.setScheme("http");
        request.addHeader("Host", "ktv.example.com");
        request.addHeader("X-Forwarded-Proto", "https");
        return request;
    }

    @Test
    void publicModeRejectsUntrustedForwardedScheme() {
        var http =
                new AuthHttp(
                        new MockEnvironment()
                                .withProperty(
                                        "singverse.auth.public-origin", "https://ktv.example.com"));
        assertThatThrownBy(() -> http.origin(request())).isInstanceOf(AuthFailure.class);
    }

    @Test
    void publicModeTrustsOnlyConfiguredProxyAndSetsSecureCookie() {
        var http =
                new AuthHttp(
                        new MockEnvironment()
                                .withProperty(
                                        "singverse.auth.public-origin", "https://ktv.example.com")
                                .withProperty("singverse.auth.trusted-proxies", "127.0.0.1/32"));
        var request = request();
        request.addHeader("Origin", "https://ktv.example.com");
        http.origin(request);
        var response = new MockHttpServletResponse();
        http.set(response, AuthHttp.SESSION, "example");
        assertThat(response.getHeader("Set-Cookie"))
                .contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://evil.example");
        assertThatThrownBy(() -> http.origin(request)).isInstanceOf(AuthFailure.class);
    }

    @Test
    void invalidPublicOriginAndWildcardProxyAreRejected() {
        assertThatThrownBy(
                        () ->
                                new AuthHttp(
                                        new MockEnvironment()
                                                .withProperty(
                                                        "singverse.auth.public-origin",
                                                        "http://ktv.example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                new AuthHttp(
                                        new MockEnvironment()
                                                .withProperty(
                                                        "singverse.auth.trusted-proxies",
                                                        "0.0.0.0/0")))
                .isInstanceOf(IllegalStateException.class);
    }
}
