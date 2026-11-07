package com.mynora.singverse.tv;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mynora.singverse.media.service.MediaPlaybackService;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TvPrefetchServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final MediaPlaybackService playback = mock(MediaPlaybackService.class);
    final AtomicLong time = new AtomicLong();
    final TvPrefetchService service = new TvPrefetchService(playback, time::get);
    final ArrayList<BooleanSupplier> guards = new ArrayList<>();

    @BeforeEach void captureGuard() {
        doAnswer(call -> { guards.add(call.getArgument(2)); return null; })
                .when(playback).prefetchOriginal(anyString(), anyString(), any());
    }

    @AfterEach void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        service.close();
    }

    ObjectNode session(long revision) {
        var n = json.createObjectNode().put("id", "session").put("status", "ACTIVE").put("revision", revision);
        n.putObject("playback").put("attemptId", "attempt").put("itemId", "current").put("songId", "current-song")
                .put("confirmed", true).put("state", "PLAYING").put("durationMs", 200_000).put("positionMs", 170_000);
        var queue = n.putArray("queue");
        queue.addObject().put("id", "current").put("songId", "current-song").put("status", "PLAYING");
        queue.addObject().put("id", "next").put("songId", "next-song").put("status", "WAITING");
        queue.addObject().put("id", "later").put("songId", "later-song").put("status", "WAITING");
        return n;
    }

    @Test void onlyRealConfirmedPlayingWithinThirtySecondsStartsOneNextOriginal() {
        long revision = 0;
        for (String state : new String[] {"PREPARING", "BUFFERING", "PAUSED", "ERROR", "ENDED", "IDLE"}) {
            var n = session(++revision); ((ObjectNode) n.path("playback")).put("state", state);
            service.changed(n, false, true);
        }
        for (long position : new long[] {0, 169_999, 200_000, Long.MAX_VALUE}) {
            var n = session(++revision); ((ObjectNode) n.path("playback")).put("positionMs", position);
            service.changed(n, false, true);
        }
        var unconfirmed = session(++revision); ((ObjectNode) unconfirmed.path("playback")).put("confirmed", false);
        service.changed(unconfirmed, false);
        var unknown = session(++revision); ((ObjectNode) unknown.path("playback")).remove("durationMs");
        service.changed(unknown, false);
        verifyNoInteractions(playback);
        service.changed(session(++revision), false, true);
        service.changed(session(++revision), false, true);
        verify(playback, times(1)).prefetchOriginal(eq("next-song"), eq("tv:session"), any());
        assertThat(guards.getFirst().getAsBoolean()).isTrue();
    }

    @Test void replacingNextInvalidatesOldGuardAndLateCommittedSnapshotCannotRestoreIt() {
        service.changed(session(10), false, true);
        var replacement = session(12);
        ((ObjectNode) replacement.path("queue").get(1)).put("status", "CANCELLED");
        service.changed(replacement, false);
        assertThat(guards.getFirst().getAsBoolean()).isFalse();
        assertThat(guards.getLast().getAsBoolean()).isTrue();
        service.changed(session(11), false);
        verify(playback).cancelOriginalPrefetch("tv:session");
        verify(playback, times(1)).prefetchOriginal(eq("next-song"), anyString(), any());
        verify(playback, times(1)).prefetchOriginal(eq("later-song"), anyString(), any());
        service.changed(session(13), false); // Reordering back does not repeat an attempted target.
        assertThat(guards.getLast().getAsBoolean()).isFalse();
        verify(playback, times(1)).prefetchOriginal(eq("next-song"), anyString(), any());
    }

    @Test void stopBlocksOldAttemptBeforeIdleAndNewAttemptMayPrefetch() {
        service.changed(session(1), false, true);
        service.changed(session(2), true);
        assertThat(guards.getFirst().getAsBoolean()).isFalse();
        service.changed(session(3), false); // Late PLAYING / ack-only response after STOP.
        verify(playback, times(1)).prefetchOriginal(anyString(), anyString(), any());
        var restarted = session(4); ((ObjectNode) restarted.path("playback")).put("attemptId", "new-attempt");
        service.changed(restarted, false, true);
        verify(playback, times(2)).prefetchOriginal(eq("next-song"), eq("tv:session"), any());
    }

    @Test void failedRegistrationDoesNotChangeSessionOrRetryOnEveryHeartbeat() {
        doThrow(new IllegalStateException("cache full")).when(playback).prefetchOriginal(anyString(), anyString(), any());
        var n = session(1); String before = n.toString();
        assertThatCode(() -> service.changed(n, false, true)).doesNotThrowAnyException();
        assertThat(n.toString()).isEqualTo(before);
        service.changed(session(2), false, true);
        verify(playback, times(1)).prefetchOriginal(anyString(), anyString(), any());
    }

    @Test void cacheIntentRunsOnlyAfterCommitAndRollbackSchedulesNothing() {
        TransactionSynchronizationManager.initSynchronization();
        service.changed(session(1), false, true);
        var rolledBack = TransactionSynchronizationManager.getSynchronizations();
        verifyNoInteractions(playback);
        rolledBack.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        verifyNoInteractions(playback);

        TransactionSynchronizationManager.initSynchronization();
        service.changed(session(2), false, true);
        var committed = TransactionSynchronizationManager.getSynchronizations();
        verifyNoInteractions(playback);
        committed.forEach(TransactionSynchronization::afterCommit);
        verify(playback).prefetchOriginal(eq("next-song"), eq("tv:session"), any());
    }

    @Test void lostHeartbeatCancelsAndDoesNotReissueTheSameSpeculativeDownload() {
        service.changed(session(1), false, true);
        time.set(31_000_000_000L);
        assertThat(guards.getFirst().getAsBoolean()).isFalse();
        service.expire();
        verify(playback).cancelOriginalPrefetch("tv:session");
        service.changed(session(2), false, true);
        verify(playback, times(1)).prefetchOriginal(anyString(), anyString(), any());
    }

    @Test void queueEditsCannotReviveStaleProgressOrTreatRestoredStateAsANewHeartbeat() {
        service.changed(session(1), false); // Persisted PLAYING after a server restart is not fresh telemetry.
        verifyNoInteractions(playback);
        service.changed(session(2), false, true);
        time.set(31_000_000_000L);
        service.changed(session(3), false);
        assertThat(guards.getFirst().getAsBoolean()).isFalse();
        var reordered = session(4);
        ((ObjectNode) reordered.path("queue").get(1)).put("status", "CANCELLED");
        service.changed(reordered, false);
        verify(playback, never()).prefetchOriginal(eq("later-song"), anyString(), any());
        reordered.put("revision", 5);
        service.changed(reordered, false, true);
        verify(playback).prefetchOriginal(eq("later-song"), eq("tv:session"), any());
    }

    @Test void revocationAndShutdownInvalidateLateCallbacks() {
        service.changed(session(1), false, true);
        service.revoked("session");
        service.changed(session(500), false);
        assertThat(guards.getFirst().getAsBoolean()).isFalse();
        verify(playback, times(1)).prefetchOriginal(anyString(), anyString(), any());
        service.close();
        service.changed(session(Long.MAX_VALUE), false);
        verify(playback, times(1)).prefetchOriginal(anyString(), anyString(), any());
    }
}
