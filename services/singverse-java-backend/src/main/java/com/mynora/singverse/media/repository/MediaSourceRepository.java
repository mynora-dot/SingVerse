package com.mynora.singverse.media.repository;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.infrastructure.database.DatabaseInitializer;
import com.mynora.singverse.media.dto.MediaSourceDtos.Source;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Repository
public class MediaSourceRepository {
    private final MediaSourceMapper mapper;

    public MediaSourceRepository(MediaSourceMapper mapper, DatabaseInitializer database) {
        this.mapper = mapper;
    }

    private Source source(Map<String, Object> row) {
        return new Source(
                row.get("id").toString(),
                row.get("name").toString(),
                row.get("source_type").toString(),
                row.get("location").toString(),
                row.get("username").toString(),
                row.get("parser_profile_id").toString(),
                row.get("password_encrypted") != null,
                row.get("created_at").toString(),
                row.get("updated_at").toString(), ((Number) row.get("scan_enabled")).intValue() == 1,
                (String) row.get("scan_cron"), (String) row.get("next_scan_at"), (String) row.get("last_scan_at"),
                (String) row.get("last_scan_status"), (String) row.get("last_scan_error"));
    }

    public List<Source> list(int page, int size) {
        return mapper.list(size, (long) page * size).stream().map(this::source).toList();
    }

    public com.mynora.singverse.media.dto.MediaSourceDtos.Page search(String keyword, String type, String profileId, int page, int size) {
        return new com.mynora.singverse.media.dto.MediaSourceDtos.Page(
                mapper.search(keyword, type, profileId, size, (long) page * size).stream().map(this::source).toList(),
                page, size, mapper.searchCount(keyword, type, profileId));
    }
    public long count() {
        return mapper.count();
    }

    public Map<String, Object> find(String id) {
        var rows = mapper.find(id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体库不存在");
        return rows.getFirst();
    }

    public Source get(String id) {
        return source(find(id));
    }

    public void delete(String id) {
        if (mapper.delete(id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体库不存在");
    }

    public Source save(String id, String name, String type, String location, String username,
            String encrypted, String profileId, String now, boolean create) {
        try {
            String hash = AuthCrypto.hash(type + ":" + location);
            if (create)
                mapper.insert(id, name, type, location, hash, username, encrypted, profileId, now, now);
            else if (mapper.update(name, location, hash, username, encrypted, profileId, now, id)
                    == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体库不存在");
        } catch (DataAccessException e) {
            if (e instanceof DuplicateKeyException
                    || (e.getMostSpecificCause() instanceof org.sqlite.SQLiteException sqlite
                            && sqlite.getResultCode()
                                    == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "此目录或 WebDAV 地址已添加");
            }
            throw e;
        }
        return get(id);
    }
}
