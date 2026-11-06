package com.mynora.singverse.mobile

import org.junit.Assert.*
import org.junit.Test

class PlaybackAudioTracksTest {
    private val declaration = PlaybackAudioTrack(0, 0, false, "audio/mp4a-latm", -1, -1)
    private val decoded = PlaybackAudioTrack(1, 0, true, "audio/mp4a-latm", 2, 48000)
    private val decoderFormat = PlaybackAudioFormat("audio/mp4a-latm", 2, 48000)

    @Test fun duplicatedSingleSourceHlsExposesOnlyTheDecodedRendition() {
        val actual = PlaybackAudioTracks.usable(listOf(declaration, decoded), 1, true, decoderFormat)
        assertEquals(listOf(decoded), actual)
        // Keep Media3's original indices so role selection targets the real group.
        assertEquals("1:0", actual.single().id)
    }

    @Test fun realMultipleSourcesKeepTracksEvenBeforeTheirParametersAreKnown() {
        assertEquals(listOf(declaration, decoded), PlaybackAudioTracks.usable(listOf(declaration, decoded), 2, true, decoderFormat))
        val second = declaration.copy(channelCount = 2, sampleRate = 48000)
        assertEquals(listOf(second, decoded), PlaybackAudioTracks.usable(listOf(second, decoded), 2, false, decoderFormat))
    }

    @Test fun unknownSourceCountOrDistinctHlsUrisCannotEstablishADuplicate() {
        val tracks = listOf(declaration, decoded)
        assertEquals(tracks, PlaybackAudioTracks.usable(tracks, 0, true, decoderFormat))
        assertEquals(tracks, PlaybackAudioTracks.usable(tracks, 1, false, decoderFormat))
    }

    @Test fun aSelectedDeclarationIsNotHiddenBeforeAudioIsDecoded() {
        val tracks = listOf(declaration.copy(selected = true), decoded.copy(selected = false))
        assertEquals(tracks, PlaybackAudioTracks.usable(tracks, 1, true, decoderFormat))
        val unknownRate = listOf(declaration, decoded.copy(sampleRate = -1))
        assertEquals(unknownRate, PlaybackAudioTracks.usable(unknownRate, 1, true, null))
        assertEquals(listOf(unknownRate[1]), PlaybackAudioTracks.usable(unknownRate, 1, true, decoderFormat))
        assertEquals(unknownRate, PlaybackAudioTracks.usable(unknownRate, 1, true, decoderFormat.copy(sampleRate = -1)))
    }

    @Test fun anyActualParametersOrDifferentAudioFormatPreserveAnUnselectedTrack() {
        for (other in listOf(
            declaration.copy(channelCount = 2),
            declaration.copy(sampleRate = 48000),
            declaration.copy(sampleMimeType = "audio/ac3"),
        )) {
            val tracks = listOf(other, decoded)
            assertEquals(tracks, PlaybackAudioTracks.usable(tracks, 1, true, decoderFormat))
        }
    }

    @Test fun ambiguousSelectionsOrAdditionalGroupsArePreserved() {
        for (tracks in listOf(
            listOf(declaration, decoded.copy(selected = false)),
            listOf(declaration.copy(selected = true), decoded),
            listOf(declaration, decoded, declaration.copy(groupIndex = 2)),
        )) assertEquals(tracks, PlaybackAudioTracks.usable(tracks, 1, true, decoderFormat))
    }
}
