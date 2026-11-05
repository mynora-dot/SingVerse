package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class TvPlayerModelsTest {
    @Test fun mediaModeUsesActualStreamsAndIgnoresAttachedCoverArt() {
        val audio = TvPlayerStream("audio", codec = "flac")
        val artwork = TvPlayerStream("video", codec = "mjpeg", attachedPicture = true)
        assertEquals(TvPlayerMode.AUDIO, tvPlayerMode(listOf(audio, artwork), observedVideo = true))
        assertEquals(TvPlayerMode.VIDEO, tvPlayerMode(listOf(audio, TvPlayerStream("video", codec = "h264"))))
        assertEquals(TvPlayerMode.UNKNOWN, tvPlayerMode(listOf(artwork)))
        assertEquals(TvPlayerMode.UNKNOWN, tvPlayerMode(emptyList()))
        assertEquals(TvPlayerMode.VIDEO, tvPlayerMode(emptyList(), observedVideo = true, observedAudio = true))
        assertEquals(TvPlayerMode.AUDIO, tvPlayerMode(emptyList(), observedAudio = true))
    }

    @Test fun audioQualityShowsOnlyReportedValuesWithoutInventingGrades() {
        assertEquals(listOf("FLAC", "44.1 kHz", "2 声道"), tvAudioQualityLabels("flac", TvPlayerStream("audio", "flac", 2, 44100)))
        assertEquals(listOf("MP4", "AAC", "320 kbps", "48 kHz", "6 声道"), tvAudioQualityLabels("mp4", TvPlayerStream("audio", "audio/mp4a-latm", 6, 48000, 320000)))
        assertEquals(listOf("128.125 kbps", "22.05 kHz"), tvAudioQualityLabels(stream = TvPlayerStream("audio", sampleRateHz = 22050, bitrateBps = 128125)))
        assertEquals(emptyList<String>(), tvAudioQualityLabels(stream = TvPlayerStream("audio", "null", -1, -1, -1)))
        assertEquals(emptyList<String>(), tvAudioQualityLabels(stream = TvPlayerStream("video", "h264", 2, 48000, 10000000)))
        assertEquals(listOf("AAC"), tvAudioQualityLabels(stream = TvPlayerStream("audio", "mp4a.40.2")))
        assertEquals(listOf("MP4A.6B"), tvAudioQualityLabels(stream = TvPlayerStream("audio", "mp4a.6b")))
    }

    @Test fun metadataHidesAbsentValuesAndDoesNotInventYearOrStyle() {
        assertEquals(emptyList<Pair<String, String>>(), tvSongMetadataLabels("null", "", listOf("", "null")))
        assertEquals(listOf("专辑" to "真实专辑", "风格" to "民谣 / 流行", "年份" to "2014", "时长" to "4:55"),
            tvSongMetadataLabels(" 真实专辑 ", "2014-09-23", listOf("民谣", "流行", "民谣"), 295000))
        assertEquals(listOf("发行" to "待考证"), tvSongMetadataLabels(releaseDate = "待考证"))
        assertEquals("1:01:01", tvPlayerDuration(3661000))
    }

    private data class Item(val id: String, val status: String, val songId: String = "same-song")

    @Test fun previewMatchesStartWhenWaitingItemWasMovedAboveHandoff() {
        val queue = listOf(Item("current", "PLAYING"), Item("new-top", "WAITING"), Item("handoff", "HANDOFF"), Item("finished", "COMPLETED"))
        assertEquals("handoff", tvNextQueuedItem(queue, Item::status, Item::id, "current")?.id)
        assertEquals("new-top", tvNextQueuedItem(queue, Item::status, Item::id, "handoff")?.id)
        assertNull(tvNextQueuedItem(listOf(Item("current", "HANDOFF")), Item::status, Item::id, "current"))
        // A later queue entry may legitimately repeat the currently singing song.
        assertEquals("repeat", tvNextQueuedItem(listOf(Item("current", "PLAYING"), Item("repeat", "WAITING")), Item::status, Item::id, "current")?.id)
    }

    @Test fun lyricsFollowExistingTimelineBoundariesAndPreviewTheNextLine() {
        val lines = TextTimeline.parse("lrc", "[00:01.00]第一句\n[00:03.00]第二句\n[00:05.00]第三句")
        assertEquals(TvLyricPreview(null, lines[0]), tvLyricPreview(lines, 999))
        assertEquals(TvLyricPreview(lines[0], lines[1]), tvLyricPreview(lines, 1000))
        assertEquals(TvLyricPreview(lines[1], lines[2]), tvLyricPreview(lines, 3000))
        assertEquals(TvLyricPreview(lines[2], null), tvLyricPreview(lines, 5000))
        assertEquals(TvLyricPreview(null, null), tvLyricPreview(emptyList(), 3000))
    }

    @Test fun subtitleGapsAndEnhancedWordTimingRemainUnchanged() {
        val subtitle = TextTimeline.parse("srt", "1\n00:00:01,000 --> 00:00:02,000\n第一句\n\n2\n00:00:04,000 --> 00:00:05,000\n第二句")
        assertEquals(TvLyricPreview(null, subtitle[1]), tvLyricPreview(subtitle, 2500))
        assertEquals(TvLyricPreview(null, subtitle[1]), tvLyricPreview(subtitle, 2000))
        assertEquals(TvLyricPreview(null, null), tvLyricPreview(subtitle, 5000))
        val enhanced = TextTimeline.parse("lrc", "[00:01.00]<00:01.00>唱<00:01.50>歌\n[00:03.00]下一句")
        val preview = tvLyricPreview(enhanced, 1600)
        assertEquals(listOf(TimedWord(1000, "唱"), TimedWord(1500, "歌")), preview.current?.words)
        assertEquals(enhanced[1], preview.next)
    }

    @Test fun previewUsesActualPausedPositionAndExistingSignedLyricOffset() {
        val lines = TextTimeline.parse("lrc", "[00:01.00]第一句\n[00:03.00]第二句\n[00:05.00]第三句")
        val pausedPlaybackPosition = 3500L
        val paused = tvLyricPreview(lines, pausedPlaybackPosition)
        assertEquals(TvLyricPreview(lines[1], lines[2]), paused)
        // No timer advances a paused lyric: repeated snapshots use the same real position.
        repeat(3) { assertEquals(paused, tvLyricPreview(lines, pausedPlaybackPosition)) }
        // PlaybackOptions subtracts positive delay; negative delay brings lyrics forward.
        assertEquals(TvLyricPreview(lines[0], lines[1]), tvLyricPreview(lines, pausedPlaybackPosition - 1000))
        assertEquals(TvLyricPreview(lines[2], null), tvLyricPreview(lines, pausedPlaybackPosition - (-2000)))
    }
}
