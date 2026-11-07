package com.mynora.singverse.tv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.service.MediaPlaybackService;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Speculative original-file downloads never create playback attempts or compute work. */
@Service
public class TvPrefetchService {
    static final long MAX_DURATION_MS = 86_400_000;
    private static final long WINDOW_MS = 30_000;
    private static final long HEARTBEAT_NANOS = 30_000_000_000L;
    private static final long RETAIN_NANOS = 86_400_000_000_000L;
    private final MediaPlaybackService playback;
    private final LongSupplier clock;
    private final Map<String, Slot> sessions = new HashMap<>();
    private boolean closed;

    private record Target(String attempt, String item, String song) {}
    private record Update(String session, long revision, String attempt, Target target, boolean trigger, boolean stop, boolean progress) {}
    private static final class Ticket {
        volatile boolean active = true;
        volatile long heartbeat;
        Ticket(long now) { heartbeat = now; }
    }
    private static final class Slot {
        long revision = -1;
        long updated;
        long progress;
        boolean reported;
        String attempt = "";
        String blockedAttempt = "";
        Target target;
        final Set<Target> attempted = new HashSet<>();
        Ticket ticket;
    }

    @Autowired
    public TvPrefetchService(MediaPlaybackService playback) {
        this(playback, System::nanoTime);
    }

    TvPrefetchService(MediaPlaybackService playback, LongSupplier clock) {
        this.playback = playback;
        this.clock = clock;
    }

    void changed(ObjectNode session, boolean stop) {
        changed(session, stop, false);
    }

    void changed(ObjectNode session, boolean stop, boolean progress) {
        var p = session.path("playback");
        String attempt = p.path("attemptId").asText();
        boolean current = false;
        JsonNode next = null;
        for (var item : session.path("queue")) {
            if (item.path("id").asText().equals(p.path("itemId").asText())
                    && item.path("status").asText().equals("PLAYING")) current = true;
            if (next == null && item.path("status").asText().equals("WAITING")) next = item;
        }
        boolean eligible = session.path("status").asText().equals("ACTIVE") && p.path("confirmed").asBoolean()
                && !attempt.isBlank() && current && Set.of("PLAYING", "PAUSED", "BUFFERING").contains(p.path("state").asText());
        var target = eligible && next != null
                ? new Target(attempt, next.path("id").asText(), next.path("songId").asText()) : null;
        long duration = p.path("durationMs").asLong(), position = p.path("positionMs").asLong();
        boolean trigger = p.path("state").asText().equals("PLAYING") && duration > 0 && duration <= MAX_DURATION_MS
                && position > 0 && position < duration && duration - position <= WINDOW_MS;
        var update = new Update(session.path("id").asText(), session.path("revision").asLong(), attempt, target, trigger, stop, progress);
        afterCommit(() -> apply(update));
    }

    void revoked(String session) {
        if (!session.isBlank()) afterCommit(() -> apply(new Update(session, Long.MAX_VALUE, "", null, false, true, false)));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private synchronized void apply(Update update) {
        if (closed) return;
        var slot = sessions.computeIfAbsent(update.session(), ignored -> new Slot());
        if (update.revision() <= slot.revision) return;
        slot.revision = update.revision();
        slot.updated = clock.getAsLong();
        if (!slot.attempt.equals(update.attempt())) {
            slot.attempt = update.attempt();
            slot.attempted.clear();
            slot.reported = false;
        }
        if (update.progress()) { slot.progress = slot.updated; slot.reported = true; }
        if (update.stop()) slot.blockedAttempt = update.attempt();
        var target = update.attempt().equals(slot.blockedAttempt) ? null : update.target();
        if (!Objects.equals(slot.target, target)) {
            cancel(update.session(), slot);
            slot.target = target;
        }
        if (target == null) return;
        if (slot.ticket != null) {
            slot.ticket.heartbeat = slot.progress;
        } else if (update.trigger() && slot.reported && slot.updated - slot.progress <= HEARTBEAT_NANOS && slot.attempted.add(target)) {
            var ticket = new Ticket(slot.progress);
            slot.ticket = ticket;
            try {
                // This method only registers intent; the cache worker performs all DB/network IO.
                playback.prefetchOriginal(target.song(), owner(update.session()),
                        () -> ticket.active && clock.getAsLong() - ticket.heartbeat <= HEARTBEAT_NANOS);
            } catch (RuntimeException ignored) {
                // Cache pressure or failure must not change playback/queue or retry every progress event.
                cancel(update.session(), slot);
            }
        }
    }

    private static String owner(String session) { return "tv:" + session; }

    private void cancel(String session, Slot slot) {
        if (slot.ticket == null) return;
        slot.ticket.active = false;
        slot.ticket = null;
        try { playback.cancelOriginalPrefetch(owner(session)); }
        catch (RuntimeException ignored) { /* The inactive ticket also invalidates worker callbacks. */ }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000, scheduler = "transcodeScheduler")
    synchronized void expire() {
        long now = clock.getAsLong();
        sessions.entrySet().removeIf(entry -> {
            var slot = entry.getValue();
            if (slot.ticket != null && now - slot.ticket.heartbeat > HEARTBEAT_NANOS) cancel(entry.getKey(), slot);
            return now - slot.updated > RETAIN_NANOS;
        });
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        sessions.forEach(this::cancel);
        sessions.clear();
    }
}
