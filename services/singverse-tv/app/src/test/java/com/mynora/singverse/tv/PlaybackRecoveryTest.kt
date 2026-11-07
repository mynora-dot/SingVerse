package com.mynora.singverse.tv

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun codecFailureRetriesOnlyOnceAndRetainsCompatibilityUntilNextAttempt() {
        val recovery = PlaybackRecovery()
        assertTrue(recovery.begin(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(recovery.forceCompatibility)
        assertFalse(recovery.begin(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(recovery.forceCompatibility)
        recovery.reset()
        assertFalse(recovery.forceCompatibility)
        assertTrue(recovery.begin(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    @Test fun networkAndAudioRouteFailuresDoNotRequestTranscodingOrConsumeCodecRetry() {
        val recovery = PlaybackRecovery()
        for (code in listOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)) {
            assertFalse(recovery.begin(code))
            assertFalse(recovery.forceCompatibility)
        }
        assertTrue(recovery.begin(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
    }

    @Test fun mapsRealAvcCapabilitiesWithoutClaimingHighBitDepthProfiles() {
        assertEquals("Baseline", PlaybackCapabilities.avcProfile(CodecProfileLevel.AVCProfileBaseline))
        assertEquals("Main", PlaybackCapabilities.avcProfile(CodecProfileLevel.AVCProfileMain))
        assertEquals("High", PlaybackCapabilities.avcProfile(CodecProfileLevel.AVCProfileHigh))
        assertNull(PlaybackCapabilities.avcProfile(CodecProfileLevel.AVCProfileHigh10))
        assertEquals(31, PlaybackCapabilities.avcLevel(CodecProfileLevel.AVCLevel31))
        assertEquals(41, PlaybackCapabilities.avcLevel(CodecProfileLevel.AVCLevel41))
        assertEquals(0, PlaybackCapabilities.avcLevel(Int.MAX_VALUE))
    }

    @Test fun higherDecoderLevelsKeepTheCompatibilityEncoderAtLevel41() {
        for (level in listOf(CodecProfileLevel.AVCLevel41, CodecProfileLevel.AVCLevel42, CodecProfileLevel.AVCLevel5,
            CodecProfileLevel.AVCLevel51, CodecProfileLevel.AVCLevel52, CodecProfileLevel.AVCLevel6,
            CodecProfileLevel.AVCLevel61, CodecProfileLevel.AVCLevel62)) {
            assertEquals(41, PlaybackCapabilities.avcLevel(level))
        }
        val highest = PlaybackCapabilities.avcCandidate(mapOf("High" to PlaybackCapabilities.avcLevel(CodecProfileLevel.AVCLevel62)), 8_000_000) { _, _, _ -> true }!!
        assertEquals(mapOf("High" to 41), highest.profiles)
        assertEquals(1920, highest.width)
        assertEquals(1080, highest.height)
        assertEquals(30, highest.fps)
    }

    @Test fun weakOptionalDecoderCannotReduceAvcResolutionOrBitrate() {
        val avc = PlaybackCapabilities.DecoderLimit(1920, 1080, 8_000_000)
        val weakAv1 = PlaybackCapabilities.DecoderLimit(854, 480, 2_000_000)
        val hevc = PlaybackCapabilities.DecoderLimit(1920, 1080, 8_000_000)
        val accepted = PlaybackCapabilities.compatibleVideoLimits(listOf("h264" to avc, "av1" to weakAv1, "hevc" to hevc))
        assertEquals(listOf("h264", "hevc"), accepted.map { it.first })
        assertEquals(1920, accepted.minOf { it.second.width })
        assertEquals(8_000_000, accepted.minOf { it.second.bitrate })
    }

    @Test fun oneDecoderMustSupportTheSameReportedProfileLevelAndPictureTogether() {
        val baseline = PlaybackCapabilities.avcCandidate(mapOf("Baseline" to 41, "High" to 31), 8_000_000) { _, _, _ -> true }!!
        assertEquals(mapOf("Baseline" to 41), baseline.profiles)
        assertEquals(1920, baseline.width)
        val high = PlaybackCapabilities.avcCandidate(mapOf("Baseline" to 31, "High" to 41), 8_000_000) { _, _, _ -> true }!!
        assertEquals(mapOf("High" to 41), high.profiles)
        assertEquals(1920, high.width)
        val level31 = PlaybackCapabilities.avcCandidate(mapOf("High" to 31), 8_000_000) { _, _, _ -> true }!!
        assertEquals(1280, level31.width)
        assertEquals(720, level31.height)
        assertEquals(30, level31.fps)
    }

    @Test fun level1bRanksAboveLevel1AndBelowLevel11() {
        val level1b = PlaybackCapabilities.avcCandidate(mapOf("Baseline" to 10, "Main" to 9), 128_000) { _, _, _ -> true }!!
        assertEquals(mapOf("Main" to 9), level1b.profiles)
        assertEquals(176, level1b.width)
        assertEquals(15, level1b.fps)
        val level11 = PlaybackCapabilities.avcCandidate(mapOf("Baseline" to 11, "Main" to 9), 192_000) { _, _, _ -> true }!!
        assertEquals(mapOf("Baseline" to 11), level11.profiles)
    }
}
