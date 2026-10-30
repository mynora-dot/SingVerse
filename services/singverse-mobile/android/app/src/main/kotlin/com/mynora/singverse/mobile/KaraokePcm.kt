package com.mynora.singverse.mobile

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** File and timing primitives also exercised on the JVM with deterministic signals. */
object KaraokePcm {
    const val RATE = 48000
    fun header(file: File, channels: Int) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { out ->
            val size = (out.length() - 44).coerceAtLeast(0).toInt()
            val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(channels.toShort()).putInt(RATE)
                .putInt(RATE * channels * 2).putShort((channels * 2).toShort()).putShort(16)
                .put("data".toByteArray()).putInt(size)
            out.seek(0); out.write(b.array())
        }
    }
    fun create(file: File) = RandomAccessFile(file, "rw").apply { setLength(44); seek(44) }
    fun frames(file: File, channels: Int) = ((file.length() - 44).coerceAtLeast(0) / (channels * 2))
    fun offsetFrames(voiceZeroNs: Long, playbackZeroNs: Long) = ((voiceZeroNs - playbackZeroNs) * RATE / 1_000_000_000L)
    fun mix(backing: Int, voice: Int, backingGain: Double, voiceGain: Double): Short {
        val signal = backing * backingGain + voice * voiceGain
        return signal.coerceIn(-32767.0, 32767.0).roundToInt().toShort()
    }
    fun waveform(file: File, channels: Int, bins: Int = 80): List<Double> {
        val count = frames(file, channels)
        if (count <= 0) return emptyList()
        return RandomAccessFile(file, "r").use { input -> (0 until bins).map { bin ->
            val start = count * bin / bins
            val end = (count * (bin + 1) / bins).coerceAtLeast(start + 1)
            input.seek(44 + start * channels * 2)
            var peak = 0
            repeat(min(256L, end - start).toInt() * channels) {
                if (input.filePointer + 2 <= input.length()) {
                    val s = java.lang.Short.reverseBytes(input.readShort()).toInt()
                    peak = max(peak, abs(s))
                }
            }
            peak / 32768.0
        } }
    }
}
