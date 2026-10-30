package com.mynora.singverse.mobile

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

object KaraokeEncoder {
    fun validate(file: File) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            require(extractor.trackCount == 1) { "混音文件校验失败，原始录音已保留" }
            val format = extractor.getTrackFormat(0)
            require(format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" && format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2 && format.getLong(MediaFormat.KEY_DURATION) > 0) { "混音格式或时长无效" }
        } finally { extractor.release() }
    }

    fun encode(backing: File, voice: File, output: File, frames: Long, voiceOffset: Long, backingGain: Double, voiceGain: Double) {
        require(backing.isFile && voice.isFile && frames > 0) { "录音素材不完整" }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false; var track = -1; var position = 0L; var eos = false; var finished = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", KaraokePcm.RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(backing, "r").use { b -> RandomAccessFile(voice, "r").use { v ->
                b.seek(44)
                var lastProgress = System.nanoTime()
                while (!finished) {
                    var progress = false
                    if (!eos) {
                        val input = codec.dequeueInputBuffer(10000)
                        if (input >= 0) {
                            val buffer = codec.getInputBuffer(input)!!; buffer.clear(); buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val count = min((buffer.remaining() / 4).toLong(), frames - position).toInt()
                            val musicBytes = ByteArray(count * 4)
                            val musicCount = min(musicBytes.size.toLong(), (b.length() - b.filePointer).coerceAtLeast(0)).toInt()
                            b.readFully(musicBytes, 0, musicCount)
                            val voiceBytes = ByteArray(count * 2)
                            val voiceStart = position - voiceOffset
                            val padding = (-voiceStart).coerceIn(0, count.toLong()).toInt()
                            val readStart = voiceStart.coerceAtLeast(0)
                            val voiceCount = min((count - padding).toLong(), (KaraokePcm.frames(voice, 1) - readStart).coerceAtLeast(0)).toInt()
                            if (voiceCount > 0) { v.seek(44 + readStart * 2); v.readFully(voiceBytes, padding * 2, voiceCount * 2) }
                            val musicBuffer = java.nio.ByteBuffer.wrap(musicBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val humanBuffer = java.nio.ByteBuffer.wrap(voiceBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            repeat(count) {
                                val human = humanBuffer.short.toInt()
                                repeat(2) { buffer.putShort(KaraokePcm.mix(musicBuffer.short.toInt(), human, backingGain, voiceGain)) }
                            }
                            val time = position * 1_000_000 / KaraokePcm.RATE
                            position += count; eos = position >= frames
                            codec.queueInputBuffer(input, 0, count * 4, time, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            progress = true
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { check(!started); track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true; progress = true }
                        else -> if (index >= 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                check(started); muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                            }
                            finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false); progress = true
                        }
                    }
                    if (progress) lastProgress = System.nanoTime()
                    check(System.nanoTime() - lastProgress < 30_000_000_000L) { "混音编码超时，录音已保留" }
                }
            } }
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (started) runCatching { muxer.stop() }; muxer.release()
        }
    }
}
