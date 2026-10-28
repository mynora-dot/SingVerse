package com.mynora.singverse.media.metadata;

import java.util.EnumMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** One request per provider, at least 1.5 seconds between starts; no account cookies. */
@Component
public class ProviderCallGuard {
    private final EnumMap<MusicProvider, Semaphore> permits = new EnumMap<>(MusicProvider.class);
    private final EnumMap<MusicProvider, Long> next = new EnumMap<>(MusicProvider.class);
    private final EnumMap<MusicProvider, Long> cooldown = new EnumMap<>(MusicProvider.class);
    private MetadataSettingsService settings;
    @org.springframework.beans.factory.annotation.Autowired
    public ProviderCallGuard(MetadataSettingsService settings) { this(); this.settings = settings; }
    public ProviderCallGuard() {
        for (var provider : MusicProvider.values()) { permits.put(provider, new Semaphore(1, true)); next.put(provider, 0L); cooldown.put(provider, 0L); }
    }
    public <T> T call(MusicProvider provider, Supplier<T> action) {
        boolean acquired = false;
        try {
            acquired = permits.get(provider).tryAcquire(10, TimeUnit.SECONDS);
            if (!acquired) throw new MusicSourceException(provider, "平台繁忙，请稍后重试");
            if (cooldown.get(provider) > System.nanoTime()) throw new MusicSourceException(provider, "平台限流冷却中，请一分钟后重试");
            long wait = next.get(provider) - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            if (settings != null && !settings.require().providers().contains(provider)) throw new MusicSourceException(provider, "平台已停用");
            next.put(provider, System.nanoTime() + 1_500_000_000L);
            try { return action.get(); }
            catch (MusicSourceException error) {
                if (error.rateLimited()) cooldown.put(provider, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
                throw error;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new MusicSourceException(provider, "请求已取消");
        } finally { if (acquired) permits.get(provider).release(); }
    }
}
