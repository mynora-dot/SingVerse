@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class KaraokeAudioProcessorTest {
    private fun input(vararg values: Short)=ByteBuffer.allocateDirect(values.size*2).order(ByteOrder.nativeOrder()).apply { values.forEach { putShort(it) };flip() }
    @Test fun channelSelectionMixingAndFlush() {
        val p=KaraokeAudioProcessor();p.configure(AudioProcessor.AudioFormat(1000,2,C.ENCODING_PCM_16BIT));p.flush()
        p.queueInput(AudioProcessor.EMPTY_BUFFER);assertFalse(p.output.hasRemaining())
        p.channelMode="LEFT";p.queueInput(input(100,-200));val out=p.output;assertEquals(100.toShort(),out.short);assertEquals(100.toShort(),out.short)
        p.channelMode="MONO";p.queueInput(input(100,-200));assertEquals((-50).toShort(),p.output.short)
        p.delayMs=1;p.flush();p.channelMode="STEREO";p.queueInput(input(30,40,50,60));val delayed=p.output
        assertEquals(0.toShort(),delayed.short);assertEquals(0.toShort(),delayed.short);assertEquals(30.toShort(),delayed.short);assertEquals(40.toShort(),delayed.short)
        p.queueEndOfStream();val tail=p.output;assertEquals(50.toShort(),tail.short);assertEquals(60.toShort(),tail.short)
        p.flush();p.queueInput(input(70,80));assertEquals(0.toShort(),p.output.short)
    }
}
