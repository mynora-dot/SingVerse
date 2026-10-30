@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.mobile

import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Capture is AFTER Sonic so saved accompaniment matches the pitch/speed heard by the singer. */
class KaraokeAudioChain(private val onPcm: (ByteArray) -> Unit) : AudioProcessorChain {
    val channels = ChannelProcessor()
    private val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(KaraokePcm.RATE) }
    private val tap = object : BaseAudioProcessor() {
        override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (input.encoding != C.ENCODING_PCM_16BIT || input.channelCount !in 1..2)
                throw AudioProcessor.UnhandledAudioFormatException(input)
            return AudioProcessor.AudioFormat(input.sampleRate, 2, C.ENCODING_PCM_16BIT)
        }
        override fun queueInput(input: ByteBuffer) {
            if (!input.hasRemaining()) return
            val output = replaceOutputBuffer(input.remaining() * if (inputAudioFormat.channelCount == 1) 2 else 1)
            if (inputAudioFormat.channelCount == 1) {
                input.order(ByteOrder.nativeOrder())
                while (input.remaining() >= 2) { val sample = input.short; output.putShort(sample).putShort(sample) }
            } else output.put(input)
            output.flip()
            if (captureEnabled) {
                val bytes = ByteArray(output.remaining()); output.duplicate().get(bytes); onPcm(bytes)
            }
        }
    }
    @Volatile var captureEnabled = false
    @Volatile var audioTrack: AudioTrack? = null
    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(channels, sonic, tap)
    override fun applyPlaybackParameters(p: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(p.speed); sonic.setPitch(p.pitch); return p
    }
    override fun applySkipSilenceEnabled(enabled: Boolean) = false
    override fun getMediaDuration(playoutDuration: Long) = sonic.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount() = 0L
}

class ChannelProcessor : BaseAudioProcessor() {
    @Volatile var mode = "STEREO"
    override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (input.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun queueInput(input: ByteBuffer) {
            if (!input.hasRemaining()) return
        val output = replaceOutputBuffer(input.remaining())
        if (inputAudioFormat.channelCount == 2 && mode != "STEREO") {
            input.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 4) {
                val left = input.short; val right = input.short
                val sample = if (mode == "LEFT") left else if (mode == "RIGHT") right else ((left.toInt() + right) / 2).toShort()
                output.putShort(sample).putShort(sample)
            }
        } else output.put(input)
        output.flip()
    }
}
