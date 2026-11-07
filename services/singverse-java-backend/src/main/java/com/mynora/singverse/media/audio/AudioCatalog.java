package com.mynora.singverse.media.audio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.dto.MediaSongDtos.Song;
import com.mynora.singverse.media.repository.MediaSongRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.*;
import org.springframework.web.server.ResponseStatusException;

/** Shared lightweight read index. Complete job documents are loaded only for a requested page. */
@Service
public class AudioCatalog implements AutoCloseable {
    public record Changed() {}
    public record Summary(String id, String sourceId, String title, List<String> artists,
            String fingerprint, Integer streams, String state, String category, boolean failed) {
        boolean matches(AudioProcessingService.Filter filter) {
            if (filter == null) return true;
            if (!AudioProcessingService.clean(filter.songId()).isEmpty() && !id.equals(filter.songId())) return false;
            if (!AudioProcessingService.clean(filter.sourceId()).isEmpty() && !sourceId.equals(filter.sourceId())) return false;
            if (!AudioProcessingService.contains(title, filter.title())
                    || !AudioProcessingService.clean(filter.artist()).isEmpty()
                    && !AudioProcessingService.contains(String.join(" ", artists), filter.artist())) return false;
            if (filter.streams() != null && !filter.streams().equals(streams)) return false;
            String requested = AudioProcessingService.clean(filter.state());
            if (requested.equals("FAILED")) return failed;
            if (requested.equals("UNCONFIRMED")) return Integer.valueOf(2).equals(streams) && !state.equals("CONFIRMED");
            return requested.isEmpty() || requested.equals(category);
        }
    }
    public record Snapshot(List<Summary> songs, Map<String, Summary> byId, long generation, long built) {}
    private final MediaSongRepository songs;
    private final AudioRoleMapper mapper;
    private final AudioJobService jobs;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean building = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "audio-catalog"); thread.setDaemon(true); return thread;
    });
    private volatile Snapshot snapshot;
    private volatile long attempted;

    public AudioCatalog(MediaSongRepository songs, AudioRoleMapper mapper, AudioJobService jobs) {
        this.songs = songs; this.mapper = mapper; this.jobs = jobs;
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void changed(Changed ignored) { generation.incrementAndGet(); }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void libraryChanged(com.mynora.singverse.media.artist.ArtistDtos.Changed ignored) { generation.incrementAndGet(); }
    public boolean building() {
        var current = snapshot;
        return building.get() || current == null || current.generation() != generation.get();
    }
    public Snapshot current() {
        refresh();
        var current = snapshot;
        if (current == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "音频索引准备中，请稍后重试") {
            @Override public HttpHeaders getHeaders() {
                var headers = new HttpHeaders(); headers.set(HttpHeaders.RETRY_AFTER, "1"); return headers;
            }
        };
        return current;
    }
    @Scheduled(fixedDelay = 1000, initialDelay = 3000, scheduler = "audioScheduler")
    public void refresh() {
        var current = snapshot;
        long now = System.nanoTime();
        if (current != null && current.generation() == generation.get() && now - current.built() < TimeUnit.SECONDS.toNanos(30)) return;
        if (now - attempted < TimeUnit.SECONDS.toNanos(1) || !building.compareAndSet(false, true)) return;
        attempted = now;
        try { executor.submit(() -> { try { rebuild(); } finally { building.set(false); } }); }
        catch (RejectedExecutionException stopping) { building.set(false); }
    }
    void rebuild() {
        long version = generation.get();
        try {
            var records = new ArrayList<Summary>();
            var byId = new HashMap<String, Summary>();
            String cursor = "";
            while (!Thread.currentThread().isInterrupted()) {
                var batch = songs.batchAfter(cursor, null, 200);
                if (batch.isEmpty()) {
                    snapshot = new Snapshot(List.copyOf(records), Map.copyOf(byId), version, System.nanoTime());
                    return;
                }
                var statuses = statuses(batch);
                for (var song : batch) {
                    var status = statuses.get(song.id());
                    boolean failed = false;
                    for (var job : status.path("jobs"))
                        if (job.path("kind").asText().equals("SEPARATE") && job.path("state").asText().equals("FAILED")) failed = true;
                    var summary = new Summary(song.id(), song.sourceId(), AudioProcessingService.title(song),
                            List.copyOf(AudioProcessingService.artists(song)), MediaSongRepository.fingerprint(song),
                            status.has("streams") ? status.path("streams").size() : null,
                            status.path("state").asText(), AudioProcessingService.category(status), failed);
                    records.add(summary); byId.put(summary.id(), summary);
                }
                cursor = batch.getLast().id();
            }
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(AudioCatalog.class).warn("Audio catalog refresh failed; retaining previous snapshot");
        }
    }
    Map<String, ObjectNode> statuses(List<Song> batch) {
        if (batch.isEmpty()) return Map.of();
        var roles = new HashMap<String, Map<String,Object>>();
        for (var row : mapper.rolesForSongs(batch.stream().map(Song::id).toList()))
            roles.put(row.get("song_id").toString(), row);
        var history = new HashMap<String, List<Map<String,Object>>>();
        var identities = batch.stream().map(song -> Map.of("id", song.id(), "fingerprint", MediaSongRepository.fingerprint(song))).toList();
        for (var row : mapper.jobsForSongs(identities))
            history.computeIfAbsent(row.get("song_id").toString(), ignored -> new ArrayList<>()).add(row);
        var result = new HashMap<String, ObjectNode>();
        for (var song : batch) result.put(song.id(), jobs.status(song, roles.get(song.id()), history.getOrDefault(song.id(), List.of())));
        return result;
    }
    @Override @jakarta.annotation.PreDestroy public void close() { executor.shutdownNow(); }
}
