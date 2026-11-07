package com.mynora.singverse.mobile

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.*
import org.junit.Test

class PlaybackCapabilitiesTest {
    @Test fun level1bRanksBetween10And11DespiteFfprobeUsingTheNumberNine() {
        val support = PlaybackCapabilities.candidate(listOf("Baseline" to 10, "Main" to 9), 128_000) { _, _, _ -> true }!!
        assertEquals(9, support.level); assertEquals(listOf("Main"), support.profiles)
        assertEquals(11, PlaybackCapabilities.candidate(listOf("Main" to 9, "Baseline" to 11), 128_000) { _, _, _ -> true }!!.level)
    }
    @Test fun lowLevelLimitsDimensionsAndRateDespiteOptimisticDecoderSizeRange() {
        val support = PlaybackCapabilities.candidate(listOf("Baseline" to 31), 20_000_000) { _, _, _ -> true }!!
        assertEquals(1280, support.width); assertEquals(720, support.height); assertEquals(30, support.fps)
        assertEquals(8_000_000, support.bitrate)
        val smallest = PlaybackCapabilities.candidate(listOf("Baseline" to 10), 64_000) { _, _, _ -> true }!!
        assertEquals(176, smallest.width); assertEquals(144, smallest.height); assertEquals(15, smallest.fps)
    }
    @Test fun neverPairsAHighProfileWithAnotherProfilesHigherLevel() {
        val support = PlaybackCapabilities.candidate(listOf("Baseline" to 41, "High" to 31), 8_000_000) { _, _, _ -> true }!!
        assertEquals(listOf("Baseline"), support.profiles); assertEquals(41, support.level)
    }
    @Test fun actualVideoCapabilitiesCanReduceTheReportedPictureAndFrameRate() {
        val support = PlaybackCapabilities.candidate(listOf("Main" to 41), 3_000_000) { width, height, fps -> width <= 960 && height <= 540 && fps <= 24 }!!
        assertEquals(960, support.width); assertEquals(540, support.height); assertEquals(24, support.fps)
        assertEquals(3_000_000, support.bitrate)
        assertNull(PlaybackCapabilities.candidate(emptyList(), 8_000_000) { _, _, _ -> true })
        assertNull(PlaybackCapabilities.candidate(listOf("Main" to 41), 8_000_000) { _, _, _ -> false })
    }
    @Test fun reportsFfprobeLevelUnitsAndRejectsUnsupportedBitDepthProfiles() {
        assertEquals(31, PlaybackCapabilities.level(CodecProfileLevel.AVCLevel31))
        assertEquals(9, PlaybackCapabilities.level(CodecProfileLevel.AVCLevel1b))
        assertEquals(41, PlaybackCapabilities.level(CodecProfileLevel.AVCLevel52))
        assertEquals("High", PlaybackCapabilities.profile(CodecProfileLevel.AVCProfileHigh))
        assertNull(PlaybackCapabilities.profile(CodecProfileLevel.AVCProfileHigh10))
        assertNull(PlaybackCapabilities.profile(CodecProfileLevel.AVCProfileConstrainedHigh))
    }
}
