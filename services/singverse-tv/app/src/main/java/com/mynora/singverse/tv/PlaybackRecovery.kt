package com.mynora.singverse.tv

import androidx.media3.common.PlaybackException

/** One compatibility retry per confirmed attempt; network/authentication errors retain their cause. */
internal class PlaybackRecovery {
    var forceCompatibility = false
        private set
    private var attempted = false

    fun reset() { forceCompatibility = false; attempted = false }

    fun begin(errorCode: Int): Boolean {
        if (attempted || errorCode !in setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) return false
        attempted = true
        forceCompatibility = true
        return true
    }
}
