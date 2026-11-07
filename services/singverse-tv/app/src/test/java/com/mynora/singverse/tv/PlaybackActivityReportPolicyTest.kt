package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class PlaybackActivityReportPolicyTest {
    @Test fun expiredActivityStopsHeartbeatButKeepsQueueTerminalControls() {
        listOf("PREPARING", "PLAYING", "PAUSED", "BUFFERING", "ERROR").forEach {
            assertFalse(it, shouldReportPlaybackActivity("attempt", "attempt", it))
        }
        assertTrue(shouldReportPlaybackActivity("attempt", "attempt", "ENDED"))
        assertTrue(shouldReportPlaybackActivity("attempt", "attempt", "IDLE"))
    }

    @Test fun missingOrDifferentAttemptCannotInheritAnOldExpiration() {
        assertFalse(shouldReportPlaybackActivity("", "old", "ENDED"))
        assertTrue(shouldReportPlaybackActivity("new", "old", "PREPARING"))
        assertTrue(shouldReportPlaybackActivity("new", "old", "PLAYING"))
    }
}
