package com.mynora.singverse.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackHistoryReporterTest {
    private class Backend(override val identity: Any = "nas-A") : PlaybackHistoryReporter.Transport {
        val created = CountDownLatch(1)
        val release = CountDownLatch(1)
        var block = false
        var unsupported = false
        var failNextSend = false
        var expired = false
        var failureStatus = 0
        var registeredStartedAt = 0L
        @Volatile var valid = true
        val events = CopyOnWriteArrayList<Pair<Long, PlaybackHistoryReporter.Frame>>()
        override fun current() = valid
        override fun create(startedAt: Long): PlaybackHistoryReporter.Grant {
            registeredStartedAt = startedAt
            created.countDown()
            if (block) check(release.await(3, TimeUnit.SECONDS))
            if (unsupported) throw PlaybackHistoryReporter.Failure(404)
            return PlaybackHistoryReporter.Grant("attempt", "test-only-token")
        }
        override fun send(grant: PlaybackHistoryReporter.Grant, sequence: Long, frame: PlaybackHistoryReporter.Frame) {
            if (expired) throw PlaybackHistoryReporter.Failure(410)
            if (failureStatus != 0) throw PlaybackHistoryReporter.Failure(failureStatus)
            events.add(sequence to frame)
            if (failNextSend) { failNextSend = false; throw IOException("simulated lost response") }
        }
        override fun cancel() { valid = false; release.countDown() }
    }
    private fun frame(state: String, actual: Long = 0, elapsed: Long = actual) = PlaybackHistoryReporter.Frame(state, actual, elapsed, 120000)
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(message, predicate())
    }

    @Test fun aBlockedCreateCoalescesHundredsOfProgressFramesWithoutBlockingCaller() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend().apply { block = true }
        try {
            val attempt = reporter.begin(backend)
            reporter.offer(attempt, frame("PREPARING")); assertTrue(backend.created.await(1, TimeUnit.SECONDS))
            repeat(500) { reporter.offer(attempt, frame("PLAYING", it.toLong(), it.toLong() + 100)) }
            backend.release.countDown()
            await("latest cumulative frame sent") { backend.events.size == 2 }
            assertEquals(listOf(0L, 1L), backend.events.map { it.first })
            assertEquals(499L, backend.events.last().second.actualPlayedMs)
        } finally { backend.release.countDown(); reporter.close() }
    }
    @Test fun staleNasGenerationNeverSendsAfterAResponseArrives() {
        val reporter = PlaybackHistoryReporter(); val old = Backend("A-generation-1").apply { block = true }; val next = Backend("A-generation-3")
        try {
            val attempt = reporter.begin(old); reporter.offer(attempt, frame("PREPARING"))
            assertTrue(old.created.await(1, TimeUnit.SECONDS)); reporter.invalidate(attempt)
            reporter.offer(reporter.begin(next), frame("PLAYING"))
            await("new generation reports") { next.events.size == 1 }
            assertTrue(old.events.isEmpty())
        } finally { old.release.countDown(); reporter.close() }
    }
    @Test fun aQueuedCompletedShortSongRetainsPreRegistrationPlaybackAndOriginalTimes() {
        val reporter = PlaybackHistoryReporter(); val blocking = Backend().apply { block = true }; val shortSong = Backend()
        try {
            reporter.offer(reporter.begin(blocking), frame("PREPARING")); assertTrue(blocking.created.await(1, TimeUnit.SECONDS))
            val completed = PlaybackHistoryReporter.Frame("COMPLETED", 9000, 10000, 9000,
                startedAt = 1_700_000_000_000L, occurredAt = 1_700_000_010_000L, lastActivityAt = 1_700_000_009_000L)
            reporter.offer(reporter.begin(shortSong), completed)
            reporter.close(); blocking.release.countDown()
            await("queued short song delivered") { shortSong.events.size == 1 }
            assertEquals(completed.startedAt, shortSong.registeredStartedAt)
            assertEquals(completed, shortSong.events.single().second)
        } finally { blocking.release.countDown(); reporter.close() }
    }
    @Test fun unsupportedOldServerDisablesOnlyThatNasGenerationAndNeverAffectsAnother() {
        val reporter = PlaybackHistoryReporter(); val old = Backend().apply { unsupported = true }; val next = Backend("nas-B")
        try {
            reporter.offer(reporter.begin(old), frame("PREPARING")); assertTrue(old.created.await(1, TimeUnit.SECONDS))
            reporter.offer(reporter.begin(next), frame("PLAYING")); await("other NAS sends") { next.events.size == 1 }
            val blocked = Backend(); reporter.offer(reporter.begin(blocked), frame("PLAYING"))
            reporter.close(); assertFalse(blocked.created.await(100, TimeUnit.MILLISECONDS)); assertTrue(old.events.isEmpty())
        } finally { reporter.close() }
    }
    @Test fun terminalEventDrainsAfterCloseAndCannotBeOverwrittenByAPause() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend().apply { block = true }
        val attempt = reporter.begin(backend)
        reporter.offer(attempt, frame("PREPARING")); assertTrue(backend.created.await(1, TimeUnit.SECONDS))
        reporter.offer(attempt, frame("COMPLETED", 31000, 35000)); reporter.offer(attempt, frame("PAUSED", 31000, 36000))
        reporter.close(); backend.release.countDown()
        await("final event drained") { backend.events.size == 2 }
        assertEquals("COMPLETED", backend.events.last().second.state)
        assertEquals(31000L, backend.events.last().second.actualPlayedMs)
    }
    @Test fun errorRetryKeepsOneGrantAndCumulativeTimeWhereasExplicitStopDoesNotRevive() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend()
        try {
            val attempt = reporter.begin(backend); reporter.offer(attempt, frame("PREPARING"))
            await("registered") { backend.events.size == 1 }
            reporter.offer(attempt, frame("ERROR", 31000, 40000)); await("failed") { backend.events.size == 2 }
            reporter.offer(attempt, frame("PLAYING", 32000, 50000)); await("resumed") { backend.events.size == 3 }
            reporter.offer(attempt, frame("STOPPED", 32000, 51000)); await("stopped") { backend.events.size == 4 }
            reporter.offer(attempt, frame("PLAYING", 33000, 52000))
            assertEquals(32000L, backend.events.last().second.actualPlayedMs)
            assertEquals(listOf(0L, 1L, 2L, 3L), backend.events.map { it.first })
        } finally { reporter.close() }
    }
    @Test fun aChangedLoginCannotReuseAnOldAttemptsWriteGrant() {
        val reporter = PlaybackHistoryReporter(); val old = Backend(); val reauthenticated = Backend()
        try {
            val attempt = reporter.begin(old); reporter.offer(attempt, frame("PREPARING"))
            await("registered") { old.events.size == 1 }
            old.failureStatus = 403; reporter.offer(attempt, frame("PLAYING", 1000, 1000))
            await("old login stopped") { attempt.invalid }
            reporter.offer(reporter.begin(reauthenticated), frame("PREPARING"))
            await("new login may register even on same NAS") { reauthenticated.events.size == 1 }
        } finally { reporter.close() }
    }
    @Test fun anExpiredLeaseStopsReportingWithoutRegisteringAnAutomaticReplacement() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend()
        try {
            val attempt = reporter.begin(backend); reporter.offer(attempt, frame("PREPARING"))
            await("registered") { backend.events.size == 1 }
            backend.expired = true; reporter.offer(attempt, frame("PLAYING", 1000, 1000))
            await("expired attempt invalidated") { !backend.valid }
            repeat(10) { reporter.offer(attempt, frame("PLAYING", 2000, 2000)) }
            assertEquals(1, backend.events.size); assertTrue(attempt.invalid)
        } finally { reporter.close() }
    }
    @Test fun abandonedPreparationsWithNoPlaybackDoNotCreateHistoryAttempts() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend()
        reporter.offer(reporter.begin(backend), frame("STOPPED", 0, 1000)); reporter.close()
        assertFalse(backend.created.await(100, TimeUnit.MILLISECONDS))
    }
    @Test fun aBurstOfOldAttemptsHasOnlyEightWaitingSlots() {
        val reporter = PlaybackHistoryReporter(); val blocking = Backend().apply { block = true }
        try {
            reporter.offer(reporter.begin(blocking), frame("PREPARING")); assertTrue(blocking.created.await(1, TimeUnit.SECONDS))
            val others = (1..40).map { Backend("nas-$it") }
            others.forEach { reporter.offer(reporter.begin(it), frame("PLAYING")) }
            blocking.release.countDown()
            await("latest eight attempts sent") { others.sumOf { it.events.size } == 8 }
            assertTrue(others.take(32).all { !it.valid && it.events.isEmpty() })
            assertTrue(others.takeLast(8).all { it.events.size == 1 })
        } finally { blocking.release.countDown(); reporter.close() }
    }
    @Test fun aLostFinalResponseRetriesTheSameSequenceAndCumulativeValue() {
        val reporter = PlaybackHistoryReporter(); val backend = Backend()
        val attempt = reporter.begin(backend); reporter.offer(attempt, frame("PREPARING"))
        await("registered") { backend.events.size == 1 }
        backend.failNextSend = true
        reporter.offer(attempt, frame("STOPPED", 31000, 45000)); reporter.close()
        await("lost response retried") { backend.events.size == 3 }
        assertEquals(listOf(0L, 1L, 1L), backend.events.map { it.first })
        assertEquals(backend.events[1].second, backend.events[2].second)
    }
}
