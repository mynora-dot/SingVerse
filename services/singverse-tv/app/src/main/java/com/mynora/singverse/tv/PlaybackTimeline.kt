package com.mynora.singverse.tv

import androidx.media3.common.Player

/** Resource time zero may be the beginning of the song or a decoded suffix. */
internal fun Player.seekToOriginalPosition(positionMs: Long, resourceOffsetMs: Long) {
    seekTo((positionMs - resourceOffsetMs).coerceAtLeast(0))
}
