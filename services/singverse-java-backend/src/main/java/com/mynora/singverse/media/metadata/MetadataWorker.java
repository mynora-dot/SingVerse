package com.mynora.singverse.media.metadata;

import static com.mynora.singverse.media.metadata.MetadataDtos.*;
import static com.mynora.singverse.media.metadata.MetadataJson.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable item at a time; upstream IO never holds the SQLite connection/transaction. */
@Component
public class MetadataWorker {
    private final MetadataMapper mapper;
    private final MetadataJson json;
    private final MetadataService service;
    private final MediaSongRepository songs;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private volatile boolean ready;
    private long nextBatchRequest;
    private final MetadataSettingsService settings;
    record Work(String batchId, String itemId, String fingerprint, List<MusicProvider> platforms, Song song, boolean autoApply, double threshold) {}
    public MetadataWorker(MetadataMapper mapper, MetadataJson json, MetadataService service, MediaSongRepository songs, MetadataSettingsService settings,
            PlatformTransactionManager manager, @Value("${singverse.metadata.worker-enabled:true}") boolean enabled) {
        this.settings = settings; this.mapper = mapper; this.json = json; this.service = service; this.songs = songs;
        this.transaction = new TransactionTemplate(manager); this.enabled = enabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        transaction.executeWithoutResult(status -> { mapper.lock(); mapper.pauseActive(now()); mapper.recoverItems(now()); });
        ready = true;
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 2000)
    public void tick() { if (ready && enabled && System.nanoTime() >= nextBatchRequest) {
        try { processNext(); } finally {
            var config = settings.read();
            nextBatchRequest = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                    java.util.concurrent.ThreadLocalRandom.current().nextLong(config.minIntervalSeconds(), (long) config.maxIntervalSeconds() + 1));
        }
    } }
    public synchronized void processNext() {
        Work work = transaction.execute(status -> {
            mapper.lock(); var active = mapper.active();
            if (active.isEmpty()) return null;
            String batchId = active.getFirst().get("id").toString();
            var batch = service.batchRow(batchId);
            if (!"RUNNING".equals(batch.get("status"))) return null;
            var pending = mapper.items(batchId, "PENDING", 1, 0);
            if (pending.isEmpty()) { finish(batchId); return null; }
            var item = pending.getFirst(); String itemId = item.get("id").toString();
            if (mapper.itemStatus(itemId, "PENDING", "PROCESSING", null, null, now()) != 1) return null;
            try {
                var song = songs.get(item.get("song_id").toString());
                if (!MediaSongRepository.fingerprint(song).equals(item.get("fingerprint"))) throw conflict("歌曲文件已变化，请重新建立批次");
                return new Work(batchId, itemId, item.get("fingerprint").toString(),
                        json.decode(batch.get("providers_json"), new TypeReference<List<MusicProvider>>() {}), song,
                        ((Number) batch.get("auto_apply")).intValue() == 1, ((Number) batch.get("threshold")).doubleValue());
            } catch (RuntimeException error) {
                mapper.itemStatus(itemId, "PROCESSING", "FAILED", null, "歌曲已移除或文件发生变化", now()); finish(batchId); return null;
            }
        });
        if (work == null) return;
        SearchResult result = null; String failure = null;
        try { result = service.search(work.song(), null, work.platforms()); }
        catch (RuntimeException error) { failure = "平台请求失败、服务已停用或歌曲不可用，请检查设置后重试"; }
        SearchResult completed = result; String error = failure;
        try { transaction.executeWithoutResult(status -> {
            mapper.lock();
            var current = mapper.item(work.itemId());
            if (current.isEmpty() || !"PROCESSING".equals(current.getFirst().get("status"))) return;
            if (!"RUNNING".equals(service.batchRow(work.batchId()).get("status"))) {
                mapper.itemStatus(work.itemId(), "PROCESSING", "PENDING", null, null, now()); return;
            }
            String state = "REVIEW", message = "请人工选择候选并确认应用字段";
            List<Candidate> candidates = completed == null ? List.of() : completed.candidates().stream().limit(5).toList();
            if (error != null) { state = "FAILED"; message = error; }
            else if (candidates.isEmpty()) message = "没有找到候选，请修改关键词后从单曲入口重新匹配";
            else if (work.autoApply() && automatic(candidates, service.autoThreshold(work.threshold()))) {
                var latestSong = songs.get(work.song().id());
                if (MetadataService.hasConfirmedMetadata(latestSong)) {
                    mapper.itemStatus(work.itemId(), "PROCESSING", "MANUAL_APPLIED", json.encode(candidates), "查询期间歌曲已确认，保留已有元数据", now());
                    finish(work.batchId()); return;
                }
                try { service.applyLocked(work.song().id(), new ApplyInput(candidates.getFirst().id(), MetadataService.FIELDS), work.fingerprint()); state = "AUTO_APPLIED"; message = null; }
                catch (org.springframework.web.server.ResponseStatusException ignored) { message = "自动写入未执行，歌曲或设置已变化，请人工审核"; }
            }
            if (completed!=null && !completed.errors().isEmpty() && !Set.of("AUTO_APPLIED","MANUAL_APPLIED").contains(state)) {
                message=Objects.toString(message,"")+"；部分平台失败："+completed.errors().stream().map(e -> e.provider()+"："+e.message()).collect(java.util.stream.Collectors.joining("；"));
                if(message.length()>500) message=message.substring(0,500);
            }
            mapper.itemStatus(work.itemId(), "PROCESSING", state, json.encode(candidates), message, now());
            finish(work.batchId());
        }); } catch (RuntimeException failureDuringCommit) {
            transaction.executeWithoutResult(status -> { mapper.lock();
                mapper.itemStatus(work.itemId(), "PROCESSING", "FAILED", null, "保存失败，原元数据已保留，请重试", now());
                finish(work.batchId()); });
        }
    }
    static boolean automatic(List<Candidate> candidates, double threshold) {
        return !candidates.isEmpty() && candidates.getFirst().score() >= threshold;
    }
    private void finish(String batchId) {
        if (mapper.itemCount(batchId, "PENDING") == 0 && mapper.itemCount(batchId, "PROCESSING") == 0)
            mapper.batchStatus(batchId, "RUNNING", "COMPLETED", now());
    }
}
