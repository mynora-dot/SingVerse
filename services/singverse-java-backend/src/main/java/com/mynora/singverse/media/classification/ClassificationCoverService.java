package com.mynora.singverse.media.classification;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.*;


/** Bounded, normalized image data stored atomically with its content version. */
@Service
public class ClassificationCoverService {
    private final ClassificationMapper mapper;
    private final ClassificationService classification;
    private final TransactionTemplate tx;

    public ClassificationCoverService(
            ClassificationMapper mapper,
            ClassificationService classification,
            PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.classification = classification;
        tx = new TransactionTemplate(manager);
    }

    public ClassificationDtos.Tag upload(String id, String expected, MultipartFile file) {
        byte[] bytes = com.mynora.singverse.media.image.UploadedImage.normalize(file);
        String version;
        try {
            version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return tx.execute(
                status -> {
                    mapper.lock();
                    exists(id);
                    var old = mapper.tagCover(id);
                    if (old != null && version.equals(old.get("version"))) return tag(id);
                    check(old, expected);
                    if (old == null) mapper.insertCover(id, version, bytes);
                    else mapper.updateCover(id, version, bytes);
                    return tag(id);
                });
    }

    public ClassificationDtos.Tag remove(String id, String expected) {
        return tx.execute(
                status -> {
                    mapper.lock();
                    exists(id);
                    var old = mapper.tagCover(id);
                    if (old != null) {
                        check(old, expected);
                        mapper.deleteCover(id);
                    }
                    return tag(id);
                });
    }

    private void exists(String id) {
        if (mapper.tags().stream().noneMatch(t -> id.equals(t.get("id"))))
            throw fail(404, "分类标签不存在");
    }

    private ClassificationDtos.Tag tag(String id) {
        return classification.tags().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static void check(Map<String, Object> old, String expected) {
        if (!Objects.equals(old == null ? "" : old.get("version"), expected))
            throw fail(409, "封面已被修改，请刷新分类后重试");
    }

    public ResponseEntity<byte[]> image(String id, boolean activeOnly) {
        var row = mapper.tagCover(id);
        if (row == null || activeOnly && ((Number) row.get("active")).intValue() != 1)
            throw fail(404, "此分类暂无可用封面");
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body((byte[]) row.get("image_bytes"));
    }

    private static ResponseStatusException fail(int status, String reason) {
        return new ResponseStatusException(HttpStatus.valueOf(status), reason);
    }
}
