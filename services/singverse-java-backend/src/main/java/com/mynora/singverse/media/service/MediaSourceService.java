package com.mynora.singverse.media.service;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.dto.MediaSourceDtos.*;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class MediaSourceService {
    private final FilenameParserConfigService profiles;
    private final com.mynora.singverse.media.repository.MediaSongRepository songs;
    private final MediaSourceRepository repository;
    private final AuthCrypto crypto;
    private final com.mynora.singverse.media.repository.MediaSourceMapper schedules;

    public MediaSourceService(MediaSourceRepository repository, AuthCrypto crypto, FilenameParserConfigService profiles,
            com.mynora.singverse.media.repository.MediaSongRepository songs, com.mynora.singverse.media.repository.MediaSourceMapper schedules) {
        this.schedules = schedules;
        this.profiles = profiles; this.songs = songs;
        this.repository = repository;
        this.crypto = crypto;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page list(String q, String type, String profileId, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数超出范围");
        if (q != null && (q.length() > 200 || q.chars().anyMatch(Character::isISOControl))) throw bad("搜索文本无效");
        if (type != null && !type.isBlank() && !java.util.Set.of("LOCAL", "WEBDAV").contains(type)) throw bad("来源类型无效");
        if (profileId != null && !profileId.isBlank() && !profileId.matches("[A-Za-z0-9-]{1,36}")) throw bad("解析方案无效");
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return repository.search(pattern, type == null || type.isBlank() ? null : type, profileId == null || profileId.isBlank() ? null : profileId, page, size);
    }
    public Page list(int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) throw bad("分页参数超出范围");
        return new Page(repository.list(page, size), page, size, repository.count());
    }

    @org.springframework.transaction.annotation.Transactional
    public Source create(Input input) {
        profiles.lockBindings();
        return save(UUID.randomUUID().toString(), input, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public Source update(String id, Input input) {
        profiles.lockBindings();
        return save(id, input, repository.find(id));
    }

    public Source get(String id) {
        return repository.get(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public void delete(String id) {
        profiles.lockBindings();
        songs.delete(id);
        repository.delete(id);
    }

    private Source save(String id, Input input, Map<String, Object> previous) {
        String name = text(input.name(), 100, "名称");
        String type = input.type();
        if (!List.of("LOCAL", "WEBDAV").contains(type == null ? "" : type))
            throw bad("请选择有效的媒体库类型");
        if (previous != null && !type.equals(previous.get("source_type"))) throw bad("媒体库类型不能修改");
        String location = text(input.location(), 2048, "目录或地址");
        String username = input.username() == null ? "" : input.username().trim();
        String password = input.password();
        if (username.length() > 256
                || username.chars().anyMatch(Character::isISOControl)
                || (password != null
                        && (password.length() > 1024
                                || password.chars().anyMatch(Character::isISOControl))))
            throw bad("WebDAV 认证信息格式无效");
        if (type.equals("LOCAL")) {
            if (!username.isEmpty() || (password != null && !password.isEmpty()))
                throw bad("本地目录不接受远端认证信息");
            try {
                Path path = Path.of(location);
                if (!path.isAbsolute()) throw bad("请输入服务端可见的绝对目录路径");
                path = path.toRealPath();
                if (!Files.isDirectory(path) || !Files.isReadable(path))
                    throw bad("目录不存在或服务端没有读取权限");
                try (var entries = Files.newDirectoryStream(path)) {
                    entries.iterator().hasNext();
                }
                location = path.toString();
            } catch (java.io.IOException | InvalidPathException | SecurityException e) {
                throw bad("目录不存在或服务端无法读取，请检查挂载和权限");
            }
        } else {
            try {
                URI uri = URI.create(location).normalize();
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null
                        || uri.getRawFragment() != null
                        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                    throw bad("WebDAV 地址须为 HTTP(S) 目录地址，不能包含凭据、查询或片段");
                location =
                        new URI(
                                        uri.getScheme(),
                                        null,
                                        uri.getHost().toLowerCase(Locale.ROOT),
                                        uri.getPort(),
                                        uri.getPath().endsWith("/")
                                                ? uri.getPath()
                                                : uri.getPath() + "/",
                                        null,
                                        null)
                                .toASCIIString();
            } catch (IllegalArgumentException | java.net.URISyntaxException e) {
                throw bad("WebDAV 地址格式无效");
            }
        }
        String encrypted = null;
        if (type.equals("WEBDAV")) {
            boolean sameIdentity =
                    previous != null
                            && location.equals(previous.get("location"))
                            && username.equals(previous.get("username"));
            if (password == null && sameIdentity)
                encrypted = (String) previous.get("password_encrypted");
            else if (password == null
                    && previous != null
                    && previous.get("password_encrypted") != null)
                throw bad("更改地址或账号时，请重新输入密码，或明确清除密码");
            else if (password != null && !password.isEmpty())
                encrypted = crypto.encrypt(password, "singverse:webdav:v1");
            if (username.isEmpty() && encrypted != null) throw bad("设置密码时需要填写用户名");
        }
        String profileId = input.parserProfileId() == null
                ? previous == null ? "default" : previous.get("parser_profile_id").toString()
                : text(input.parserProfileId(), 36, "解析方案");
        try { profiles.savedProfile(profileId); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 404) throw bad("解析方案不存在，请刷新后重新选择");
            throw error;
        }
        boolean enabled = input.scanEnabled() == null ? previous != null && ((Number) previous.get("scan_enabled")).intValue() == 1 : input.scanEnabled();
        String cron = input.scanCron() == null ? previous == null ? null : (String) previous.get("scan_cron") : input.scanCron().trim();
        if (cron != null && cron.isBlank()) cron = null;
        if (cron != null) try { StandardScanCron.next(cron, Instant.now()); } catch (IllegalArgumentException error) { throw bad(error.getMessage()); }
        if (enabled && cron == null) throw bad("启用定时扫描时必须填写五段 cron 表达式");
        String next = enabled ? StandardScanCron.next(cron, Instant.now()).toString() : null;
        boolean changed = previous == null || input.scanEnabled() != null || input.scanCron() != null;
        String now = Instant.now().toString();
        repository.save(id, name, type, location, username, encrypted, profileId, now, previous == null);
        if (changed) schedules.schedule(id, enabled, cron, next);
        return repository.get(id);
    }

    private static String text(String value, int max, String field) {
        if (value == null
                || value.isBlank()
                || value.trim().length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw bad(field + "不能为空、过长或包含控制字符");
        return value.trim();
    }

    private static ResponseStatusException bad(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

}
