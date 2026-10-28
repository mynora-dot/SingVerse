package com.mynora.singverse.auth.controller;

import com.mynora.singverse.auth.dto.AuthRequests.*;

import com.mynora.singverse.auth.service.AuthService;

import com.mynora.singverse.auth.security.AuthHttp;

import jakarta.servlet.http.*;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService service;
    private final AuthHttp cookies;

    public AuthController(AuthService service, AuthHttp cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @GetMapping("/auth/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    void send(AuthService.Issued issued, HttpServletRequest request, HttpServletResponse response) {
        service.revoke(cookies.cookie(request, AuthHttp.SESSION));
        service.revoke(cookies.cookie(request, AuthHttp.CHALLENGE));
        service.revoke(cookies.cookie(request, AuthHttp.ANONYMOUS));
        cookies.clear(response);
        cookies.set(
                response,
                issued.kind().equals("session") ? AuthHttp.SESSION : AuthHttp.CHALLENGE,
                issued.token());
    }

    @PostMapping("/auth/login")
    public Map<String, String> login(
            @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        var issued = service.login(input.username(), input.password(), cookies.source(request));
        send(issued, request, response);
        return Map.of("state", issued.kind().equals("session") ? "authenticated" : "mfaRequired");
    }

    @PostMapping("/auth/mfa/verify")
    public Map<String, String> verify(
            @RequestBody Factor input, HttpServletRequest request, HttpServletResponse response) {
        var issued =
                service.complete(
                        cookies.cookie(request, AuthHttp.CHALLENGE),
                        input.code(),
                        cookies.source(request));
        send(issued, request, response);
        return Map.of("state", "authenticated");
    }

    @GetMapping("/auth/session")
    public Map<String, Object> session() {
        return service.status();
    }

    @PostMapping("/auth/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        service.revoke(cookies.cookie(request, AuthHttp.SESSION));
        cookies.clear(response);
    }

    @PostMapping("/auth/password")
    public void password(
            @RequestBody Credentials input,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.changePassword(
                input.password(), input.code(), input.newPassword(), cookies.source(request));
        cookies.clear(response);
    }

    @GetMapping("/system/parameters")
    public Map<String, Object> parameters() {
        return service.status();
    }

    @PostMapping("/system/parameters/mfa/setup")
    public AuthService.Setup setup(@RequestBody Credentials input, HttpServletRequest request) {
        return service.setup(input.password(), input.code(), cookies.source(request));
    }

    @DeleteMapping("/system/parameters/mfa/setup")
    public void cancel() {
        service.cancelSetup();
    }

    @PostMapping("/system/parameters/mfa/confirm")
    public Map<String, Object> confirm(
            @RequestBody Factor input, HttpServletRequest request, HttpServletResponse response) {
        var codes = service.confirm(input.code(), cookies.source(request));
        cookies.clear(response);
        return Map.of("recoveryCodes", codes);
    }

    @PostMapping("/system/parameters/mfa/disable")
    public void disable(
            @RequestBody Credentials input,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.disable(input.password(), input.code(), cookies.source(request));
        cookies.clear(response);
    }

    @PostMapping("/system/parameters/mfa/recovery-codes")
    public Map<String, Object> regenerate(
            @RequestBody Credentials input,
            HttpServletRequest request,
            HttpServletResponse response) {
        var codes = service.regenerate(input.password(), input.code(), cookies.source(request));
        cookies.clear(response);
        return Map.of("recoveryCodes", codes);
    }
}
