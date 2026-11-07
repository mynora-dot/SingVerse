package com.mynora.singverse.mobile

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryDurationTest {
    @Test fun liveOrDynamicPartialWindowIsNeverAShortSongsTotal() {
        assertNull(playbackHistoryDuration(0, 10000, 0, true, false))
        assertNull(playbackHistoryDuration(0, 10000, 0, false, true))
    }
    @Test fun knownSourceDurationWinsEvenForEventHls() {
        assertEquals(180000L, playbackHistoryDuration(180000, 10000, 30000, true, true))
    }
    @Test fun completedStaticResourceUsesTheOriginalTimelineIncludingOffset() {
        assertEquals(180000L, playbackHistoryDuration(0, 150000, 30000, false, false))
    }
    @Test fun unsetOverflowAndOutOfRangeDurationsAreOmitted() {
        assertNull(playbackHistoryDuration(0, -9223372036854775807L, 0, false, false))
        assertNull(playbackHistoryDuration(0, Long.MAX_VALUE, 1, false, false))
        assertNull(playbackHistoryDuration(0, 10000, -1, false, false))
    }
}
