package com.mynora.singverse.media.service;

import com.mynora.singverse.media.repository.*;
import com.mynora.singverse.media.webdav.WebDavScanService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MediaScanScheduler {
    private final MediaSourceMapper mapper;
    private final MediaSourceRepository sources;
    private final MediaScanService local;
    private final WebDavScanService webdav;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final Semaphore capacity = new Semaphore(2);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> { var t = new Thread(task, "scheduled-media-scan"); t.setDaemon(true); return t; });
    public MediaScanScheduler(MediaSourceMapper mapper, MediaSourceRepository sources, MediaScanService local,
            WebDavScanService webdav, PlatformTransactionManager manager,
            @Value("${singverse.media.scan-scheduler-enabled:true}") boolean enabled) {
        this.mapper = mapper; this.sources = sources; this.local = local; this.webdav = webdav;
        tx = new TransactionTemplate(manager); this.enabled = enabled;
    }
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void tick() {
        if (enabled) dispatch(Instant.now());
    }
    public void dispatch(Instant now) {
        for (var row : mapper.due(now.toString())) {
            if (!capacity.tryAcquire()) return;
            String id = row.get("id").toString(), cron = row.get("scan_cron").toString();
            try {
                String next = StandardScanCron.next(cron, now).toString();
                boolean claimed = Boolean.TRUE.equals(tx.execute(s -> mapper.claim(id, row.get("next_scan_at").toString(), next) == 1));
                if (!claimed) { capacity.release(); continue; }
                executor.execute(() -> {
                    try {
                        var source = sources.get(id);
                        if (!source.scanEnabled() || !cron.equals(source.scanCron())) return;
                        if (source.type().equals("WEBDAV")) { webdav.create(id); result(id, "QUEUED", null); }
                        else { local.scan(id); result(id, "SUCCEEDED", null); }
                    } catch (ResponseStatusException error) {
                        result(id, error.getStatusCode().value() == 409 ? "SKIPPED" : "FAILED", "扫描未执行或失败，请检查来源及扫描状态");
                    } catch (RuntimeException error) { result(id, "FAILED", "扫描失败，请检查目录、权限或网络后重试"); }
                    finally { capacity.release(); }
                });
            } catch (RuntimeException error) { capacity.release(); }
        }
    }
    private void result(String id, String status, String error) { mapper.scanResult(id, Instant.now().toString(), status, error); }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}
