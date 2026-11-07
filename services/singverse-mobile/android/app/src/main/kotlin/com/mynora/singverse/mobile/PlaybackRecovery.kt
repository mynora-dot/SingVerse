package com.mynora.singverse.mobile

import androidx.media3.common.PlaybackException

/** Only decoding/unsupported-format failures justify one new NAS transcode attempt. */
internal object PlaybackRecovery {
    const val PREPARATION_POLL_MS = 750L
    const val LEASE_POLL_MS = 10_000L
    const val LEASE_RETRY_MS = 1_000L
    const val LEASE_FAILURE_LIMIT_MS = 60_000L
    const val PREPARATION_LIMIT_MS = 60_000L
    fun preparationExpired(startedAt: Long, now: Long) = now - startedAt >= PREPARATION_LIMIT_MS
    /** EVENT HLS starts at offset and never slides; only seek inside its known window. */
    fun localHlsSeek(originalPosition: Long, offset: Long, duration: Long, seekable: Boolean): Long? {
        if (!seekable || duration <= 0 || originalPosition < offset) return null
        val local = originalPosition - offset
        return local.takeIf { it < duration }
    }
    fun compatible(errorCode: Int, forced: Boolean, local: Boolean, recording: Boolean): Boolean =
        !forced && !local && !recording && errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
}
