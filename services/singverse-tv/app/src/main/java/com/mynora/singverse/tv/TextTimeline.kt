package com.mynora.singverse.tv

/** Millisecond timeline shared by LRC, enhanced LRC, SRT and WebVTT. No invented timing. */
data class TimedWord(val at: Long, val text: String)
data class TextLine(val start: Long, val end: Long, val text: String, val words: List<TimedWord> = emptyList())
object TextTimeline {
    private val stamp = Regex("(\\d{1,3}):(\\d{2})(?:[.,](\\d{1,3}))?")
    fun timestamp(raw: String): Long {
        val parts = raw.trim().split(':')
        if (parts.size == 3) return parts[0].toLong() * 3600000 + timestamp(parts.drop(1).joinToString(":"))
        val m = stamp.matchEntire(raw.trim()) ?: throw IllegalArgumentException("Invalid timestamp")
        require(m.groupValues[2].toInt() < 60)
        return m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + m.groupValues[3].padEnd(3, '0').ifBlank { "0" }.toLong()
    }
    fun parse(format: String, raw: String): List<TextLine> {
        require(raw.length <= 131072)
        val text = raw.removePrefix("\uFEFF").replace("\r", "")
        if (format == "lrc") {
            val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-600000, 600000) ?: 0
            val lines = mutableListOf<TextLine>()
            for (line in text.lines()) {
                val stamps = Regex("\\[(\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?)\\]").findAll(line).toList()
                if (stamps.isEmpty()) continue
                val content = line.substring(stamps.last().range.last + 1)
                val words = Regex("<(\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?)>([^<]*)").findAll(content).map { TimedWord(timestamp(it.groupValues[1]) + offset, it.groupValues[2]) }.toList()
                val clean = content.replace(Regex("<[^>]*>"), "")
                for (m in stamps) lines.add(TextLine((timestamp(m.groupValues[1]) + offset).coerceAtLeast(0), Long.MAX_VALUE, clean, words))
            }
            val sorted = lines.sortedBy { it.start }
            return sorted.mapIndexed { i, l -> l.copy(end = sorted.getOrNull(i + 1)?.start ?: Long.MAX_VALUE) }
        }
        require(format == "srt" || format == "vtt")
        return text.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines(); val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) return@mapNotNull null
            runCatching {
                val times = lines[index].split("-->")
                val start = timestamp(times[0]); val end = timestamp(times[1].trim().substringBefore(' '))
                require(end > start)
                TextLine(start, end, lines.drop(index + 1).joinToString("\n").replace(Regex("<[^>]*>"), ""))
            }.getOrNull()
        }.sortedBy { it.start }
    }
    fun at(lines: List<TextLine>, position: Long): TextLine? = lines.lastOrNull { position >= it.start && position < it.end }
}
