package com.mynora.singverse.tv

import org.junit.Assert.*
import org.junit.Test

class TextTimelineTest {
    @Test fun enhancedLrcRepeatedStampsAndOffset() {
        val lines=TextTimeline.parse("lrc","[offset:100]\n[00:01.20]<00:01.20>你<00:01.50>好\n[00:02][00:03]重复")
        assertEquals(3,lines.size);assertEquals(1300L,lines[0].start)
        assertEquals("你好",lines[0].text);assertEquals(1600L,lines[0].words[1].at)
        assertTrue(lines[0].hasCompleteWords)
        assertFalse(lines[1].hasCompleteWords)
        assertNull(TextTimeline.at(lines,1200));assertEquals("重复",TextTimeline.at(lines,3100)?.text)
    }
    @Test fun srtVttGapsAndInvalidCues() {
        val srt=TextTimeline.parse("srt","1\n00:00:01,000 --> 00:00:02,500\n你好\n\n2\ninvalid --> broken\n坏数据")
        assertEquals(1,srt.size);assertNull(TextTimeline.at(srt,2600));assertEquals("你好",TextTimeline.at(srt,1500)?.text)
        val vtt=TextTimeline.parse("vtt","WEBVTT\n\n00:01.000 --> 00:02.000 align:center\n<i>字幕</i>")
        assertEquals("字幕",vtt[0].text);assertEquals(3723120L,TextTimeline.timestamp("01:02:03.12"))
    }
    @Test fun rejectsUntimedOrOversizedContent() {
        assertTrue(TextTimeline.parse("lrc","没有时间戳").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { TextTimeline.parse("lrc","x".repeat(131073)) }
        assertThrows(IllegalArgumentException::class.java) { TextTimeline.timestamp("00:99.2") }
    }
    @Test fun normalizesFormatsFromApiAndSettingsLabels() {
        assertEquals("歌词",TextTimeline.parse(" LRC ","[00:01.00]歌词").single().text)
        assertEquals("字幕",TextTimeline.parse("SRT","1\n00:00:01,000 --> 00:00:02,000\n字幕").single().text)
        assertEquals("字幕",TextTimeline.parse("VTT","WEBVTT\n\n00:01.000 --> 00:02.000\n字幕").single().text)
    }
    @Test fun malformedLrcCueDoesNotDiscardOtherRealLyrics() {
        val lines=TextTimeline.parse("lrc","[00:99.00]坏时间\n[00:02.000]<00:02.000>真<00:99.000>坏<00:02.500>实\n[00:04.000]下一句")
        assertEquals(listOf(2000L,4000L),lines.map{it.start})
        assertEquals("真坏实",lines.first().text)
        assertEquals(listOf(2000L,2500L),lines.first().words.map{it.at})
        assertFalse(lines.first().hasCompleteWords)
        assertEquals("下一句",TextTimeline.at(lines,4000)?.text)
    }
    @Test fun textBeforeTheFirstWordTimestampRequiresWholeLineDisplay() {
        val line=TextTimeline.parse("lrc","[00:01.00]前半句<00:01.50>后半句").single()
        assertEquals(1000L,line.start)
        assertEquals(listOf(TimedWord(1500L,"后半句")),line.words)
        assertEquals("前半句后半句",line.text)
        assertFalse(line.hasCompleteWords)
    }
}
