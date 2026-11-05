package com.mynora.singverse.tv;

import java.util.*;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import com.mynora.singverse.media.image.UploadedImage;

@Service
public class TvHomeSettingsService {
    public record Banner(String id, String label, boolean enabled, String version, String imagePath) {}
    public record Settings(long revision, String headline, String subtitle, boolean autoRotate,
            int rotationSeconds, List<Banner> banners) {}
    private final TvHomeMapper mapper;
    private final TransactionTemplate tx;
    public TvHomeSettingsService(TvHomeMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }
    public Settings read(boolean tv) {
        // Settings and ordered image metadata form a single consistent snapshot.
        return tx.execute(status -> snapshot(tv));
    }
    private Settings snapshot(boolean tv) {
        var s = mapper.settings();
        var banners = mapper.banners().stream().filter(b -> !tv || enabled(b)).map(b -> {
            String id = b.get("id").toString();
            return new Banner(id, b.get("label").toString(), enabled(b), b.get("version").toString(),
                    (tv ? "/api/v1/tv/home/banners/" : "/api/v1/admin/tv-settings/banners/") + id + "/image");
        }).toList();
        return new Settings(((Number)s.get("revision")).longValue(), s.get("headline").toString(),
                s.get("subtitle").toString(), ((Number)s.get("auto_rotate")).intValue() == 1,
                ((Number)s.get("rotation_seconds")).intValue(), banners);
    }
    private static boolean enabled(Map<String,Object> row) { return ((Number)row.get("enabled")).intValue() == 1; }
    private void check(long revision) {
        mapper.lock();
        if (((Number)mapper.settings().get("revision")).longValue() != revision)
            throw TvService.fail(409, "电视设置已被修改，请重新加载后再试");
    }
    public Settings save(Settings v) {
        if (v == null || !text(v.headline(), 80, false) || !text(v.subtitle(), 160, true)
                || v.rotationSeconds() < 5 || v.rotationSeconds() > 60 || v.banners() == null || v.banners().size() > 8)
            throw TvService.fail(400, "标题、说明或轮播间隔无效（5至60秒，最多8张图片）");
        var ids = new HashSet<String>();
        for (var banner : v.banners()) {
            if (banner == null || banner.id() == null || !ids.add(banner.id()) || !text(banner.label(), 80, false))
                throw TvService.fail(400, "封面名称或列表无效");
        }
        return tx.execute(status -> {
            check(v.revision());
            var savedIds = new HashSet<>(mapper.banners().stream().map(b -> b.get("id").toString()).toList());
            if (!ids.equals(savedIds)) throw TvService.fail(409, "封面列表已变化，请重新加载");
            if (mapper.save(v) != 1) throw TvService.fail(409, "电视设置已变化");
            for (int i = 0; i < v.banners().size(); i++) mapper.updateBanner(v.banners().get(i), i);
            return snapshot(false);
        });
    }
    private static boolean text(String value, int limit, boolean empty) {
        return value != null && value.length() <= limit && (empty || !value.isBlank()) && value.chars().noneMatch(Character::isISOControl);
    }
    public Settings upload(long revision, MultipartFile file) {
        byte[] bytes = UploadedImage.normalize(file);
        String version;
        try { version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return tx.execute(status -> {
            check(revision);
            var banners = mapper.banners();
            if (banners.size() >= 8) throw TvService.fail(400, "最多保存8张封面，请先移除不需要的图片");
            int order = banners.stream().mapToInt(b -> ((Number)b.get("sort_order")).intValue()).max().orElse(-1) + 1;
            mapper.insert(UUID.randomUUID().toString(), "首页封面 " + (banners.size() + 1), order, version, bytes);
            mapper.bump();
            return snapshot(false);
        });
    }
    public Settings remove(String id, long revision) {
        return tx.execute(status -> {
            check(revision);
            if (mapper.remove(id) != 1) throw TvService.fail(404, "封面不存在或已移除");
            mapper.bump();
            return snapshot(false);
        });
    }
    public ResponseEntity<byte[]> image(String id, boolean tv) {
        var row = mapper.image(id);
        if (row == null || tv && !enabled(row)) throw TvService.fail(404, "封面不存在或已停用");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body((byte[])row.get("image_bytes"));
    }
}
