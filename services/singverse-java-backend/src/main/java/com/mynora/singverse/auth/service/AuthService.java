package com.mynora.singverse.auth.service;

import com.mynora.singverse.auth.crypto.AuthCrypto;

import com.mynora.singverse.auth.repository.AuthStore;

import com.mynora.singverse.auth.exception.AuthFailure;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.*;
import dev.samstevens.totp.secret.DefaultSecretGenerator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@Transactional(noRollbackFor = AuthFailure.class)
public class AuthService {
    private final AuthStore store;
    private final AuthCrypto crypto;

    public AuthService(AuthStore store, AuthCrypto crypto) {
        this.store = store;
        this.crypto = crypto;
    }

    long now() {
        return System.currentTimeMillis();
    }

    public record Issued(String token, String kind) {}

    public record Setup(String secret, String qrDataUrl, String expiresAt) {}

    public void setCsrf(String token, String csrf) {
        store.mapper().setCsrf(csrf, AuthCrypto.hash(token));
    }

    public Map<String, Object> status() {
        var account = store.account();
        return Map.of(
                "username",
                "admin",
                "defaultPassword",
                store.number(account, "default_password") == 1,
                "mfaEnabled",
                account.get("mfa_secret") != null,
                "recoveryCodesRemaining",
                store.mapper().countRecovery());
    }

    public Issued issue(String kind) {
        long time = now();
        store.mapper().deleteExpiredTokens(time, time - 1800000);
        String token = AuthCrypto.token();
        store.mapper().insertToken(
                AuthCrypto.hash(token), kind, AuthCrypto.token(), time, time,
                time + (kind.equals("session") ? 28800000 : 300000),
                store.number(store.account(), "revision"));
        return new Issued(token, kind);
    }

    public Map<String, Object> lookup(String token, String kind) {
        if (token == null || token.length() != 43) return null;
        var rows =
                store.mapper().findToken(
                        AuthCrypto.hash(token), kind, now(), now() - 1800000,
                        store.number(store.account(), "revision"));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean authenticated(String token) {
        if (lookup(token, "session") == null) return false;
        store.mapper().touchToken(now(), AuthCrypto.hash(token));
        return true;
    }

    public void revoke(String token) {
        if (token != null)
            store.mapper().deleteToken(AuthCrypto.hash(token));
    }

    void checkLimit(String address) {
        for (String bucket : List.of("account:admin", "ip:" + AuthCrypto.hash(address))) {
            var rows = store.mapper().findThrottle(bucket);
            if (!rows.isEmpty()) {
                long remaining = store.number(rows.getFirst(), "blocked_until") - now();
                if (remaining > 0)
                    throw new AuthFailure(429, "尝试过于频繁，请稍后重试", (int) ((remaining + 999) / 1000));
            }
        }
    }

    void failure(String address) {
        for (String bucket : List.of("account:admin", "ip:" + AuthCrypto.hash(address))) {
            var rows = store.mapper().findThrottle(bucket);
            int count =
                    rows.isEmpty() || store.number(rows.getFirst(), "updated_at") < now() - 3600000
                            ? 1
                            : (int) store.number(rows.getFirst(), "failures") + 1;
            long delay = count < 5 ? 0 : Math.min(900000, 1000L * (1L << Math.min(count - 5, 10)));
            if (rows.isEmpty())
                store.mapper().insertThrottle(bucket, count, now() + delay, now());
            else
                store.mapper().updateThrottle(count, now() + delay, now(), bucket);
        }
        store.event("authentication_failed");
    }

    void success(String address) {
        store.mapper().clearLimits("account:admin", "ip:" + AuthCrypto.hash(address));
    }

    boolean password(String value) {
        return value != null
                && value.length() <= 256
                && store.passwords().matches(value, store.account().get("password_hash").toString());
    }

    public synchronized Issued login(String username, String password, String address) {
        checkLimit(address);
        boolean valid = password(password);
        if (!"admin".equals(username) || !valid) {
            failure(address);
            throw new AuthFailure(401, "认证失败，请检查凭据");
        }
        if (store.account().get("mfa_secret") != null) return issue("challenge");
        success(address);
        store.event("login_succeeded");
        return issue("session");
    }

    boolean verifyFactor(String code) {
        var account = store.account();
        if (code == null || code.length() > 128) return false;
        if (code.matches("[0-9]{6}")) {
            try {
                String secret = crypto.decrypt(account.get("mfa_secret").toString());
                long current = now() / 30000;
                var generator = new DefaultCodeGenerator();
                for (long step = current - 1; step <= current + 1; step++) {
                    if (MessageDigest.isEqual(
                            generator.generate(secret, step).getBytes(StandardCharsets.UTF_8),
                            code.getBytes(StandardCharsets.UTF_8))) {
                        return store.mapper().acceptTotpStep(step, step, store.number(account, "revision"))
                                == 1;
                    }
                }
            } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
                throw new IllegalStateException(e);
            }
            return false;
        }
        return store.mapper().consumeRecovery(AuthCrypto.hash(code)) == 1;
    }

    public synchronized Issued complete(String token, String code, String address) {
        checkLimit(address);
        if (lookup(token, "challenge") == null) throw new AuthFailure(401, "认证已过期，请重新登录");
        store.mapper().incrementTokenAttempts(AuthCrypto.hash(token));
        if (!verifyFactor(code)) {
            if (store.mapper().tokenAttempts(AuthCrypto.hash(token)) >= 5) revoke(token);
            failure(address);
            throw new AuthFailure(401, "认证失败，请检查凭据");
        }
        revoke(token);
        success(address);
        store.event("mfa_login_succeeded");
        return issue("session");
    }

    void reauthenticate(String password, String code, String address) {
        checkLimit(address);
        if (!password(password)
                || (store.account().get("mfa_secret") != null && !verifyFactor(code))) {
            failure(address);
            throw new AuthFailure(401, "认证失败，请检查凭据");
        }
        success(address);
    }

    public synchronized Setup setup(String password, String code, String address) {
        reauthenticate(password, code, address);
        String secret = new DefaultSecretGenerator().generate();
        long expires = now() + 300000;
        store.mapper().startMfaSetup(crypto.encrypt(secret), expires);
        try {
            var data =
                    new QrData.Builder()
                            .label("admin")
                            .issuer("SingVerse")
                            .secret(secret)
                            .algorithm(HashingAlgorithm.SHA1)
                            .digits(6)
                            .period(30)
                            .build();
            String image =
                    "data:image/png;base64,"
                            + Base64.getEncoder()
                                    .encodeToString(new ZxingPngQrGenerator().generate(data));
            store.event("mfa_setup_started");
            return new Setup(secret, image, java.time.Instant.ofEpochMilli(expires).toString());
        } catch (Exception e) {
            throw new IllegalStateException("MFA QR generation failed", e);
        }
    }

    public synchronized void cancelSetup() {
        store.mapper().cancelMfaSetup();
    }

    public synchronized List<String> confirm(String code, String address) {
        checkLimit(address);
        var account = store.account();
        if (account.get("pending_secret") == null
                || store.number(account, "pending_expires") < now()) {
            cancelSetup();
            throw new AuthFailure(409, "绑定已过期，请重新开始");
        }
        String secret = crypto.decrypt(account.get("pending_secret").toString());
        long accepted = -1;
        try {
            for (long step = now() / 30000 - 1; step <= now() / 30000 + 1; step++)
                if (code != null
                        && MessageDigest.isEqual(
                                new DefaultCodeGenerator()
                                        .generate(secret, step)
                                        .getBytes(StandardCharsets.UTF_8),
                                code.getBytes(StandardCharsets.UTF_8))) accepted = step;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (accepted < 0) {
            store.mapper().incrementSetupAttempts();
            if (store.number(store.account(), "pending_attempts") >= 5) cancelSetup();
            failure(address);
            throw new AuthFailure(401, "认证失败，请检查凭据");
        }
        store.mapper().confirmMfa(accepted);
        List<String> codes = newRecovery();
        invalidate();
        success(address);
        store.event("mfa_enabled");
        return codes;
    }

    List<String> newRecovery() {
        store.mapper().deleteRecovery();
        var codes = new ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            String code = HexFormat.of().formatHex(AuthCrypto.random(16));
            codes.add(code);
            store.mapper().insertRecovery(AuthCrypto.hash(code));
        }
        return codes;
    }

    public synchronized List<String> regenerate(String password, String code, String address) {
        reauthenticate(password, code, address);
        if (store.account().get("mfa_secret") == null) throw new AuthFailure(409, "尚未开启二次验证");
        List<String> codes = newRecovery();
        invalidate();
        store.event("recovery_codes_regenerated");
        return codes;
    }

    public synchronized void disable(String password, String code, String address) {
        reauthenticate(password, code, address);
        store.mapper().disableMfa();
        store.mapper().deleteRecovery();
        invalidate();
        store.event("mfa_disabled");
    }

    public static void validatePassword(String password) {
        int length = password == null ? 0 : password.codePointCount(0, password.length());
        if (length < 15 || length > 128 || password.equals("admin"))
            throw new AuthFailure(400, "新密码需要 15～128 个字符");
    }

    public synchronized void changePassword(
            String password, String code, String next, String address) {
        validatePassword(next);
        reauthenticate(password, code, address);
        store.mapper().changePassword(store.passwords().encode(next));
        invalidate();
        store.event("password_changed");
    }

    public void invalidate() {
        store.mapper().invalidateAccount();
        store.mapper().deleteTokens();
    }
}
