package com.mynora.singverse.mobile

internal data class PlaybackAudioFormat(val sampleMimeType: String?, val channelCount: Int, val sampleRate: Int)

internal data class PlaybackAudioTrack(
    val groupIndex: Int,
    val trackIndex: Int,
    val selected: Boolean,
    val sampleMimeType: String?,
    val channelCount: Int,
    val sampleRate: Int,
) {
    val id: String get() = "$groupIndex:$trackIndex"
}

internal object PlaybackAudioTracks {
    fun usable(
        tracks: List<PlaybackAudioTrack>,
        sourceAudioCount: Int,
        duplicatedHlsAudio: Boolean,
        decodedAudio: PlaybackAudioFormat?,
    ): List<PlaybackAudioTrack> {
        // An audio-only HLS playlist can declare the same URI as both its variant
        // and audio rendition. Media3 then exposes a supported, unselected variant
        // without decoded parameters beside the actual audio rendition.
        if (!duplicatedHlsAudio || sourceAudioCount != 1 || tracks.size != 2) return tracks
        val selected = tracks.singleOrNull { it.selected } ?: return tracks
        // Chunkless track groups may never acquire a sample rate. Require the
        // decoder's actual format, rather than treating a manifest as decoded.
        if (decodedAudio == null || decodedAudio.sampleMimeType?.startsWith("audio/") != true || decodedAudio.channelCount <= 0 || decodedAudio.sampleRate <= 0) return tracks
        if (selected.sampleMimeType != decodedAudio.sampleMimeType || selected.channelCount != decodedAudio.channelCount) return tracks
        return tracks.filterNot {
            !it.selected && it.channelCount <= 0 && it.sampleRate <= 0 &&
                (it.sampleMimeType == null || it.sampleMimeType == selected.sampleMimeType)
        }
    }
}
