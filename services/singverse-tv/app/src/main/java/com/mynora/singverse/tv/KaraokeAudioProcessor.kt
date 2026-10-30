@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.tv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Device-side PCM channel selection; source files remain unchanged. */
class KaraokeAudioProcessor : BaseAudioProcessor() {
    @Volatile var channelMode = "STEREO"
    @Volatile var delayMs = 0
    private var history = ByteArray(0)
    private var cursor = 0
    private var queued = 0
    private fun delaySize() = (inputAudioFormat.sampleRate.toLong() * inputAudioFormat.bytesPerFrame * delayMs.coerceIn(0,500) / 1000).toInt().let { it - it % inputAudioFormat.bytesPerFrame }
    override fun onFlush() { history=ByteArray(delaySize());cursor=0;queued=0 }
    override fun onQueueEndOfStream() {
        if(queued>0 && history.isNotEmpty()) {
            val out=replaceOutputBuffer(history.size)
            for(i in history.indices)out.put(history[(cursor+i)%history.size])
            out.flip();queued=0
        }
    }
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if(!inputBuffer.hasRemaining())return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.channelCount == 2 && channelMode != "STEREO") {
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.short; val right = inputBuffer.short
                val sample = when (channelMode) { "LEFT" -> left; "RIGHT" -> right; else -> ((left.toInt() + right.toInt()) / 2).toShort() }
                output.putShort(sample); output.putShort(sample)
            }
        } else output.put(inputBuffer)
        val size=delaySize()
        if(history.size!=size){history=ByteArray(size);cursor=0;queued=0}
        if(size>0) {
            for(i in 0 until output.position()) {
                val next=output.get(i);output.put(i,history[cursor]);history[cursor]=next;cursor=(cursor+1)%size;queued=1
            }
        }
        output.flip()
    }
}
