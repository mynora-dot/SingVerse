package com.mynora.singverse.tv

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
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
}
