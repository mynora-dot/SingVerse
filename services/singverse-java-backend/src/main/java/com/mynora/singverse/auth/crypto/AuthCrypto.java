package com.mynora.singverse.auth.crypto;

import com.mynora.singverse.auth.recovery.AuthRecovery;

import com.mynora.singverse.auth.repository.AuthStore;
import com.mynora.singverse.media.repository.MediaSourceMapper;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

@Component
public class AuthCrypto {
    private final byte[] key;
    private static final SecureRandom RANDOM = new SecureRandom();

    public AuthCrypto(AuthStore store, MediaSourceMapper mediaSources, Environment env) throws Exception {
        this(store, mediaSources, env, null);
    }
    public AuthCrypto(AuthStore store, MediaSourceMapper mediaSources, Environment env,
            com.mynora.singverse.media.classification.ClassificationMapper classification) throws Exception {
        this(store, mediaSources, env, classification, null);
    }

    public AuthCrypto(AuthStore store, MediaSourceMapper mediaSources, Environment env,
            com.mynora.singverse.media.classification.ClassificationMapper classification,
            com.mynora.singverse.ai.AiMapper ai) throws Exception {
        this(store,mediaSources,env,classification,ai,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AuthCrypto(AuthStore store, MediaSourceMapper mediaSources, Environment env,
            com.mynora.singverse.media.classification.ClassificationMapper classification,
            com.mynora.singverse.ai.AiMapper ai, com.mynora.singverse.compute.ComputeNodeMapper compute) throws Exception {
        if (AuthRecovery.active()) {
            key = new byte[32];
            return;
        }
        String configured = env.getProperty("singverse.auth.key-file");
        Path path;
        if (configured != null && !configured.isBlank()) path = Path.of(configured);
        else {
            try (var connection = store.dataSource().getConnection()) {
                String url = connection.getMetaData().getURL();
                path =
                        url.startsWith("jdbc:sqlite:")
                                ? Path.of(url.substring(12)).resolveSibling("auth/master.key")
                                : store.root().resolve("data/auth/master.key");
            }
        }
        path = path.toAbsolutePath();
        java.util.Map<String,String> classificationSecrets = new java.util.LinkedHashMap<>();
        if (classification != null) {
            Object secret = classification.settings().get("secret_encrypted");
            if (secret != null) classificationSecrets.put(secret.toString(), "singverse:classification:v1");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String config : classification.taskConfigs()) {
                var value = json.readTree(config).path("encryptedKey");
                if (value.isTextual()) classificationSecrets.put(value.asText(), json.readTree(config).path("credentialPurpose").asText("singverse:classification:v1"));
            }
        }
        if (ai != null) for (var row : ai.secrets()) classificationSecrets.put(row.get("secret_encrypted").toString(), row.get("credential_purpose").toString());
        if (compute != null) for (String secret:compute.secrets()) classificationSecrets.put(secret,"singverse:compute-node:v1");
        if (!Files.exists(path)) {
            var account = store.account();
            if (account.get("mfa_secret") != null
                    || account.get("pending_secret") != null
                    || mediaSources.countEncryptedPasswords() > 0
                    || !classificationSecrets.isEmpty())
                throw new IllegalStateException(
                        "MFA master key missing; restore the matching key backup");
            Files.createDirectories(
                    path.getParent(),
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(path, Base64.getEncoder().encodeToString(random(32)));
        }
        if (!Files.getPosixFilePermissions(path)
                .equals(PosixFilePermissions.fromString("rw-------")))
            throw new IllegalStateException("MFA master key must have permissions 0600");
        key = Base64.getDecoder().decode(Files.readString(path).trim());
        if (key.length != 32) throw new IllegalStateException("Invalid MFA master key length");
        for (String field : List.of("mfa_secret", "pending_secret")) {
            Object value = store.account().get(field);
            if (value != null) decrypt(value.toString());
        }
        for (var secret : classificationSecrets.entrySet()) decrypt(secret.getKey(), secret.getValue());
        for (String value : mediaSources.encryptedPasswords()) {
            decrypt(value, "singverse:webdav:v1");
        }
    }

    public static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String token() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String encrypt(String value) {
        return encrypt(value, "singverse:totp:v1");
    }

    public String encrypt(String value, String purpose) {
        try {
            byte[] nonce = random(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return "v1:"
                    + Base64.getEncoder().encodeToString(nonce)
                    + ":"
                    + Base64.getEncoder()
                            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MFA encryption failed", e);
        }
    }

    public String decrypt(String value) {
        return decrypt(value, "singverse:totp:v1");
    }

    public String decrypt(String value, String purpose) {
        try {
            String[] parts = value.split(":");
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(
                    cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("MFA master key does not match stored data");
        }
    }
}
