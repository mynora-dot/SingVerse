package com.mynora.singverse.mobile

import android.content.Context
import android.media.*
import kotlin.math.max

/** Test substitution exists only as constructor injection; no product/debug command selects it. */
interface VoiceCapture {
    val isRecording: Boolean
    val routeName: String
    fun start()
    fun read(samples: ShortArray): Int
    fun timestamp(out: AudioTimestamp): Boolean
    fun stop()
    fun close()
}
class DeviceVoiceCapture(context: Context) : VoiceCapture {
    private val mic: AudioRecord
    init {
        require(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
        val min = AudioRecord.getMinBufferSize(KaraokePcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "设备不支持所需录音格式" }
        mic = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(KaraokePcm.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(min * 4, 19200)).build()
        if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); error("麦克风初始化失败，请检查输入设备") }
    }
    override val isRecording get() = mic.recordingState == AudioRecord.RECORDSTATE_RECORDING
    override val routeName get() = mic.routedDevice?.productName?.toString() ?: "系统默认麦克风"
    override fun start() = mic.startRecording()
    override fun read(samples: ShortArray) = mic.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
    override fun timestamp(out: AudioTimestamp) = mic.getTimestamp(out, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
    override fun stop() = mic.stop()
    override fun close() = mic.release()
}
