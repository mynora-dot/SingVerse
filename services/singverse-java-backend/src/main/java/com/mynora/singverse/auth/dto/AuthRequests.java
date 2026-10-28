package com.mynora.singverse.auth.dto;

public final class AuthRequests {
    private AuthRequests() {}

    public record Login(String username, String password) {}

    public record Factor(String code) {}

    public record Credentials(String password, String code, String newPassword) {}

}
