package com.mynora.singverse.media.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.mynora.singverse.media.metadata.MetadataDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;

@Service
public class MetadataSettingsService {
    private final MetadataMapper mapper;
    private final MetadataJson json;
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");
    public MetadataSettingsService(MetadataMapper mapper, MetadataJson json) { this.mapper = mapper; this.json = json; }
    public Settings read() {
        var row = mapper.settings();
        return new Settings(((Number) row.get("enabled")).intValue() == 1,
                json.decode(row.get("providers_json"), new TypeReference<List<MusicProvider>>() {}),
                ((Number) row.get("auto_apply")).intValue() == 1, ((Number) row.get("threshold")).doubleValue(),
                ((Number) row.get("min_interval_seconds")).intValue(), ((Number) row.get("max_interval_seconds")).intValue());
    }
    @Transactional
    public Settings save(Settings input) {
        if (input == null || input.providers() == null || input.providers().stream().anyMatch(Objects::isNull)
                || input.providers().size() > 3 || new HashSet<>(input.providers()).size() != input.providers().size()
                || !Double.isFinite(input.threshold()) || input.threshold() < .8 || input.threshold() > 1)
            throw bad("平台选择无效，自动写入阈值需要在 0.8～1 之间");
        if (input.enabled() && input.providers().isEmpty()) throw bad("请至少选择一个元数据平台");
        int min = input.minIntervalSeconds() == null ? 3 : input.minIntervalSeconds();
        int max = input.maxIntervalSeconds() == null ? 8 : input.maxIntervalSeconds();
        if (min < 0 || max > 300 || max < min) throw bad("批量间隔需要在 0～300 秒之间，最大值不能小于最小值");
        mapper.lock();
        mapper.pauseActive(now());
        mapper.saveSettings(input.enabled(), json.encode(input.providers()), input.autoApply(), input.threshold(), min, max, now());
        return read();
    }
    public Settings require() {
        var settings = read();
        if (!settings.enabled() || settings.providers().isEmpty()) throw conflict("请先在元数据刮削设置中启用并选择平台");
        return settings;
    }
    public String getOrCreateKugouDeviceId() { return deviceId; }
}
