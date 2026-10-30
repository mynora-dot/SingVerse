package com.mynora.singverse.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KaraokePcmTest {
    @Test fun wavHeaderAndWaveformDescribeTheActualSamples() {
        val file = File.createTempFile("karaoke-", ".wav")
        try {
            KaraokePcm.create(file).use { out ->
                val bytes = ByteBuffer.allocate(4800 * 2).order(ByteOrder.LITTLE_ENDIAN)
                repeat(4800) { bytes.putShort(if (it % 2 == 0) 12000 else -12000) }
                out.write(bytes.array())
            }
            KaraokePcm.header(file, 1)
            val bytes = file.readBytes()
            assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(48000, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
            assertEquals(4800L, KaraokePcm.frames(file, 1))
            assertTrue(KaraokePcm.waveform(file, 1).all { it in .36.. .37 })
        } finally { file.delete() }
    }
    @Test fun captureClockAlignmentUsesMonotonicFrameOrigins() {
        val start = 8_000_000_000L
        assertEquals(4800L, KaraokePcm.offsetFrames(start + 100_000_000, start))
        assertEquals(-2400L, KaraokePcm.offsetFrames(start - 50_000_000, start))
        assertEquals(0L, KaraokePcm.offsetFrames(start, start))
    }
    @Test fun mixPreservesBothInputsAndLimitsOverflow() {
        assertEquals(9000.toShort(), KaraokePcm.mix(10000, 6000, .6, .5))
        assertEquals(32767.toShort(), KaraokePcm.mix(32000, 32000, 2.0, 2.0))
        assertEquals((-32767).toShort(), KaraokePcm.mix(-32000, -32000, 2.0, 2.0))
        assertEquals(6000.toShort(), KaraokePcm.mix(0, 6000, 1.0, 1.0))
    }
}
