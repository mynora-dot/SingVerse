package com.mynora.singverse.tv;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Explicit administrator-managed release directory. Never accepts APK uploads from TV. */
@Service
public class TvReleaseService {
    private final Path root;
    private final ObjectMapper json;

    public TvReleaseService(
            @Value("${singverse.tv.release-directory:}") String directory, ObjectMapper json) {
        this.root = directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
        this.json = json;
    }

    public Path apk() {
        if (root == null) throw TvService.fail(404, "尚未发布 TV 安装包");
        try {
            Path file = root.resolve("singverse-tv.apk");
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file)
                    || !file.toRealPath().startsWith(root.toRealPath()))
                throw new java.io.IOException();
            return file;
        } catch (Exception e) {
            throw TvService.fail(404, "TV 安装包不可用");
        }
    }

    public Map<String, Object> current() {
        if (root == null) return Map.of("available", false);
        try {
            Path manifest = root.resolve("release.json");
            if (Files.isSymbolicLink(manifest)
                    || Files.size(manifest) > 8192
                    || !manifest.toRealPath().startsWith(root.toRealPath()))
                throw new IllegalArgumentException();
            var metadata = json.readTree(Files.readString(manifest));
            long version = metadata.path("versionCode").asLong();
            String name = metadata.path("versionName").asText();
            if (version < 1 || name.isBlank() || name.length() > 80)
                throw new IllegalArgumentException();
            Path file = apk();
            long size = Files.size(file);
            if (size < 1 || size > 268435456) throw new IllegalArgumentException();
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1; ) digest.update(buffer, 0, n);
            }
            return Map.of(
                    "available",
                    true,
                    "versionCode",
                    version,
                    "versionName",
                    name,
                    "size",
                    size,
                    "sha256",
                    HexFormat.of().formatHex(digest.digest()),
                    "url",
                    "/api/v1/tv/releases/apk");
        } catch (Exception e) {
            throw TvService.fail(503, "TV 发布目录内容不完整或无效");
        }
    }
}
