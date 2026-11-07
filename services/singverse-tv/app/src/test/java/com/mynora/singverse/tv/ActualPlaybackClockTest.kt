package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class ActualPlaybackClockTest {
    @Test fun onlyActualPlayingCountsAcrossPauseBufferingAndSeek() {
        var now = 10L; val counter = ActualPlaybackClock { now }; counter.start("song-attempt")
        now += 4000; assertEquals(0L, counter.sample(true).actualPlayedMs)
        now += 5000; assertEquals(5000L, counter.sample(false).actualPlayedMs)
        // A seek, a buffer refill and a pause can move media position arbitrarily, never this clock.
        now += 120000; assertEquals(ActualPlaybackClock.Sample(5000, 129000), counter.sample(false))
        now += 1000; counter.sample(true)
        now += 2500; assertEquals(7500L, counter.sample(false).actualPlayedMs)
    }
    @Test fun duplicateAndSameAttemptResourceRebuildDoNotResetOrDoubleCount() {
        var now = 0L; val counter = ActualPlaybackClock { now }; counter.start("same")
        counter.sample(true); now = 30000
        assertEquals(30000L, counter.sample(true).actualPlayedMs)
        assertEquals(30000L, counter.sample(true).actualPlayedMs)
        assertFalse(counter.start("same"))
        counter.sample(false); now = 45000; counter.sample(true); now = 50000
        assertEquals(ActualPlaybackClock.Sample(35000, 50000), counter.sample(false))
    }
    @Test fun newSongRestartOrCompleteLoopGetsAFreshCounter() {
        var now = 0L; val counter = ActualPlaybackClock { now }; counter.start("first")
        counter.sample(true); now = 10000; counter.sample(false)
        assertTrue(counter.start("next-loop")); assertEquals(ActualPlaybackClock.Sample(0, 0), counter.sample(true))
        now = 12000; assertEquals(ActualPlaybackClock.Sample(2000, 2000), counter.sample(false))
    }
    @Test fun clearedLocalWorkAndIdleNeverAccumulate() {
        var now = 0L; val counter = ActualPlaybackClock { now }; counter.start("nas-song"); counter.sample(true)
        now = 2000; counter.sample(false); counter.clear(); now = 100000
        assertEquals(ActualPlaybackClock.Sample(0, 0), counter.sample(true))
    }
    @Test fun regressingClockCannotSubtractOrCountTheSameIntervalTwice() {
        var now = 100L; val counter = ActualPlaybackClock { now }; counter.start("same"); counter.sample(true)
        now = 200; assertEquals(100L, counter.sample(true).actualPlayedMs)
        now = 150; assertEquals(100L, counter.sample(true).actualPlayedMs)
        now = 250; assertEquals(150L, counter.sample(false).actualPlayedMs)
    }
    @Test fun pausedAndBufferedSnapshotsKeepActualActivityTimeAndUseTheMonotonicUtcAnchor() {
        var now = 100L; val counter = ActualPlaybackClock { now }; val epoch = 1_700_000_000_000L
        counter.start("song", epoch); counter.sample(false)
        assertNull(counter.lastActivityAtEpochMs)
        now = 1100; counter.sample(true)
        now = 2100; counter.sample(false)
        assertEquals(epoch + 2000, counter.lastActivityAtEpochMs)
        now = 62200; counter.sample(false)
        assertEquals(epoch + 62100, counter.occurredAtEpochMs)
        assertEquals(epoch + 2000, counter.lastActivityAtEpochMs)
        assertEquals(epoch, counter.startedAtEpochMs)
        assertFalse(counter.start("song", epoch + 999999))
        assertEquals(epoch, counter.startedAtEpochMs)
    }

    @Test fun confirmedLoadCanRenewForMoreThanTwoMinutesWithoutInventingPlayingActivity() {
        var now = 0L
        val counter = ActualPlaybackClock { now }
        val epoch = 1_700_000_000_000L
        counter.start("nas:session:confirmed-attempt", epoch)
        var leaseUntil = epoch + 120_000
        for (heartbeat in 0..10) {
            now = heartbeat * 15_000L
            val frame = counter.sample(false)
            assertTrue("PREPARING must be sent before media is installed",
                shouldReportPlaybackActivity("confirmed-attempt", "", "PREPARING"))
            assertTrue(counter.occurredAtEpochMs < leaseUntil)
            assertEquals(ActualPlaybackClock.Sample(0, now), frame)
            assertNull(counter.lastActivityAtEpochMs)
            leaseUntil = counter.occurredAtEpochMs + 120_000
        }
        assertFalse("Resource installation keeps the LOAD's original UTC anchor",
            counter.start("nas:session:confirmed-attempt", epoch + now))
        counter.sample(true)
        now += 30_000
        assertEquals(ActualPlaybackClock.Sample(30_000, 180_000), counter.sample(false))
        assertEquals(epoch, counter.startedAtEpochMs)
    }
}
