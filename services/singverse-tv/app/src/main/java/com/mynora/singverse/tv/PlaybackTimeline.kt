package com.mynora.singverse.tv

import androidx.media3.common.Player

/** Resource time zero may be the beginning of the song or a decoded suffix. */
internal fun Player.seekToOriginalPosition(positionMs: Long, resourceOffsetMs: Long) {
    seekTo((positionMs - resourceOffsetMs).coerceAtLeast(0))
}

/** EVENT HLS retains all segments from offset; only already exposed, seekable time can stay local. */
internal fun localPlaybackPosition(positionMs: Long, resourceOffsetMs: Long, durationMs: Long, seekable: Boolean): Long? {
    if (!seekable || durationMs <= 0 || resourceOffsetMs < 0 || positionMs < resourceOffsetMs) return null
    return (positionMs - resourceOffsetMs).takeIf { it < durationMs }
}

/** An unfinished EVENT HLS window is not a total; only a known source duration is safe there. */
internal fun originalPlaybackDurationMs(
    sourceDurationMs: Long,
    playerDurationMs: Long,
    resourceOffsetMs: Long,
    dynamic: Boolean,
    live: Boolean
): Long? {
    val maximum = 24L * 60 * 60 * 1000
    if (sourceDurationMs in 1..maximum) return sourceDurationMs
    if (dynamic || live) return null
    if (playerDurationMs <= 0 || resourceOffsetMs !in 0 until maximum || playerDurationMs > maximum - resourceOffsetMs) return null
    return playerDurationMs + resourceOffsetMs
}
