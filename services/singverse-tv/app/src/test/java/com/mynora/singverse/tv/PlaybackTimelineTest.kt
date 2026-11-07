package com.mynora.singverse.tv

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    private val seeks = mutableListOf<Long>()
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
        check(method.name == "seekTo" && arguments?.size == 1) { "Unexpected player call: ${method.name}" }
        seeks += arguments!![0] as Long
        null
    } as Player

    @Test fun fullSongHlsCacheSeeksToRequestedSongPosition() {
        player.seekToOriginalPosition(100_215, 0)
        assertEquals(listOf(100_215L), seeks)
    }

    @Test fun decodedSuffixStartsAtZeroAndKeepsOriginalTimeline() {
        player.seekToOriginalPosition(100_215, 100_215)
        assertEquals(listOf(0L), seeks)
        assertEquals(100_215L, seeks.single() + 100_215)
    }

    @Test fun positionInsideDecodedSuffixUsesItsLocalTimeline() {
        player.seekToOriginalPosition(100_215, 80_000)
        assertEquals(listOf(20_215L), seeks)
        assertEquals(100_215L, seeks.single() + 80_000)
    }

    @Test fun directMediaRetainsAbsoluteSeekingAndUnavailablePrefixClampsToZero() {
        player.seekToOriginalPosition(30_000, 0)
        player.seekToOriginalPosition(30_000, 40_000)
        assertEquals(listOf(30_000L, 0L), seeks)
    }

    @Test fun availableHlsTimeSeeksLocallyWithTheOriginalOffset() {
        assertEquals(20_000L, localPlaybackPosition(100_000, 80_000, 40_000, true))
        assertEquals(0L, localPlaybackPosition(80_000, 80_000, 40_000, true))
        assertEquals(100_000L, localPlaybackPosition(100_000, 0, 240_000, true))
    }

    @Test fun hlsPrefixOrUnproducedSuffixRequiresNewResource() {
        assertNull(localPlaybackPosition(79_999, 80_000, 40_000, true))
        assertNull(localPlaybackPosition(120_000, 80_000, 40_000, true))
        assertNull(localPlaybackPosition(120_001, 80_000, 40_000, true))
    }

    @Test fun unknownOrUnseekableTimelineRequiresNewResource() {
        assertNull(localPlaybackPosition(90_000, 80_000, androidx.media3.common.C.TIME_UNSET, true))
        assertNull(localPlaybackPosition(90_000, 80_000, 0, true))
        assertNull(localPlaybackPosition(90_000, 80_000, 40_000, false))
    }

    @Test fun positiveSourceDurationWinsOverThePartialHlsTimeline() {
        assertEquals(240_000L, originalPlaybackDurationMs(240_000, 20_000, 80_000, false, false))
    }

    @Test fun staticPlayerDurationIncludesTheResourceOffsetOnlyWhenSourceDurationIsUnknown() {
        assertEquals(240_000L, originalPlaybackDurationMs(0, 160_000, 80_000, false, false))
        assertEquals(40_000L, originalPlaybackDurationMs(0, 40_000, 0, false, false))
    }

    @Test fun unknownSourceDurationDoesNotUseAnUnfinishedDynamicHlsWindow() {
        assertNull(originalPlaybackDurationMs(0, 12_000, 0, true, false))
        assertNull(originalPlaybackDurationMs(0, 12_000, 80_000, true, true))
    }

    @Test fun unknownSourceDurationDoesNotUseALiveWindowEvenIfItIsNotDynamic() {
        assertNull(originalPlaybackDurationMs(0, 12_000, 80_000, false, true))
    }

    @Test fun knownSourceDurationRemainsAvailableForDynamicAndLiveHls() {
        assertEquals(240_000L, originalPlaybackDurationMs(240_000, 12_000, 80_000, true, true))
        assertEquals(240_000L, originalPlaybackDurationMs(240_000, 12_000, 80_000, true, false))
        assertEquals(240_000L, originalPlaybackDurationMs(240_000, 12_000, 80_000, false, true))
    }

    @Test fun unknownPlayerDurationNeverBecomesTheResourceOffset() {
        assertNull(originalPlaybackDurationMs(0, androidx.media3.common.C.TIME_UNSET, 80_000, false, false))
        assertNull(originalPlaybackDurationMs(0, 0, 80_000, false, false))
        assertNull(originalPlaybackDurationMs(0, -1, 80_000, false, false))
    }

    @Test fun invalidOrOverflowingDurationsAreOmittedWithoutLosingAValidFallback() {
        val maximum = 24L * 60 * 60 * 1000
        assertEquals(40_000L, originalPlaybackDurationMs(Long.MAX_VALUE, 40_000, 0, false, false))
        assertEquals(maximum, originalPlaybackDurationMs(0, maximum - 80_000, 80_000, false, false))
        assertNull(originalPlaybackDurationMs(0, maximum, 1, false, false))
        assertNull(originalPlaybackDurationMs(0, Long.MAX_VALUE, 80_000, false, false))
        assertNull(originalPlaybackDurationMs(0, 40_000, -1, false, false))
    }
}
