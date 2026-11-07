package com.mynora.singverse.mobile

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun preparationHasAnAbsoluteDeadlineIndependentOfSuccessfulPolls() {
        assertFalse(PlaybackRecovery.preparationExpired(1000, 60_999))
        assertTrue(PlaybackRecovery.preparationExpired(1000, 61_000))
        assertTrue(PlaybackRecovery.preparationExpired(1000, 90_000))
    }
    @Test fun seeksWithinTheCurrentEventTimelineWithoutRebuildingItsSession() {
        assertEquals(15_000L, PlaybackRecovery.localHlsSeek(45_000, 30_000, 60_000, true))
        assertEquals(0L, PlaybackRecovery.localHlsSeek(30_000, 30_000, 60_000, true))
        assertNull(PlaybackRecovery.localHlsSeek(29_999, 30_000, 60_000, true))
        assertNull(PlaybackRecovery.localHlsSeek(90_000, 30_000, 60_000, true))
        assertNull(PlaybackRecovery.localHlsSeek(45_000, 30_000, -9223372036854775807L, true))
        assertNull(PlaybackRecovery.localHlsSeek(45_000, 30_000, 60_000, false))
    }
    @Test fun onlyDecoderAndUnsupportedFormatErrorsGetOneCompatibleAttempt() {
        for (code in listOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) {
            assertTrue(PlaybackRecovery.compatible(code, false, false, false))
            assertFalse(PlaybackRecovery.compatible(code, true, false, false))
            assertFalse(PlaybackRecovery.compatible(code, false, true, false))
            assertFalse(PlaybackRecovery.compatible(code, false, false, true))
        }
        for (code in listOf(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            assertFalse(PlaybackRecovery.compatible(code, false, false, false))
        }
    }
}
