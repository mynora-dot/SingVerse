package com.mynora.singverse.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import kotlin.math.*

class KaraokeEngine(private val context: Context, private val track: () -> AudioTrack?, private val changed: () -> Unit,
                    private val interrupt: (String) -> Unit, private val inputFactory: () -> VoiceCapture = { DeviceVoiceCapture(context) }) {
    val works = KaraokeWorks(context).apply { recover() }
    @Volatile var phase = "IDLE"; private set
    @Volatile var takeId = ""; private set
    @Volatile var level = 0.0; private set
    @Volatile var clipped = false; private set
    @Volatile private var capturing = false
    @Volatile private var recorder: VoiceCapture? = null
    @Volatile private var voiceFrames = 0L
    @Volatile private var backingFrames = 0L
    @Volatile private var voiceZeroNs = 0L
    @Volatile private var playbackZeroNs = 0L
    @Volatile private var voiceTimestampAvailable = false
    @Volatile private var audibleFrames = 0L
    @Volatile private var error = ""
    private var reader: Thread? = null
    private var backingWriter: Thread? = null
    private val pending = ArrayBlockingQueue<ByteArray>(64)
    private val jobs = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var startedNs = 0L
    private var meta = JSONObject()
    @Volatile private var busyRender = false

    private fun microphone(): VoiceCapture = inputFactory()

    fun start(song: JSONObject, server: String, settings: JSONObject): String {
        require(phase !in listOf("RECORDING", "TESTING", "FINALIZING", "RENDERING")) { "请先结束当前录音或等待处理完成" }
        require(works.root.usableSpace > 64L * 1024 * 1024) { "手机空间不足，无法开始录唱" }
        val mic = microphone()
        val id = UUID.randomUUID().toString()
        val voice = KaraokePcm.create(works.file(id, "voice.wav"))
        val backing = KaraokePcm.create(works.backing(id))
        meta = JSONObject().put("id", id).put("song", song).put("instanceId", server)
            .put("name", song.optString("title", "我的演唱")).put("createdAt", System.currentTimeMillis())
            .put("settings", settings).put("status", "RECORDING").put("complete", false)
        works.put(meta)
        takeId = id; error = ""; voiceFrames = 0; backingFrames = 0; audibleFrames = 0
        voiceZeroNs = 0; playbackZeroNs = 0; voiceTimestampAvailable = false; level = 0.0; clipped = false; pending.clear()
        recorder = mic; startedNs = System.nanoTime()
        try { mic.start(); require(mic.isRecording) { "麦克风未开始采集" } }
        catch (e: Exception) { voice.close(); backing.close(); mic.close(); phase = "FAILED"; error = e.message ?: "麦克风启动失败"; meta.put("status", "FAILED").put("interruption", error); works.put(meta); changed(); throw e }
        capturing = true; phase = "RECORDING"
        backingWriter = Thread({
            try {
                while (capturing || pending.isNotEmpty()) {
                    val bytes = pending.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    backing.write(bytes); backingFrames += bytes.size / 4
                }
            } catch (e: Exception) { reportFailure("伴奏写入失败，已保留录音片段") }
            finally { backing.close() }
        }, "karaoke-backing").apply { start() }
        reader = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(960)
            val time = AudioTimestamp()
            var checkedAt = 0L
            try {
                while (capturing) {
                    val count = mic.read(samples)
                    if (count < 0) throw IOException("音频采集已中断")
                    if (count == 0) continue
                    val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var peak = 0; var sum = 0.0
                    repeat(count) { val value = samples[it].toInt(); bytes.putShort(samples[it]); peak = max(peak, abs(value)); sum += value.toDouble() * value }
                    voice.write(bytes.array()); voiceFrames += count
                    level = sqrt(sum / count) / 32768.0; clipped = peak >= 32760
                    if (mic.timestamp(time) && time.nanoTime >= startedNs) {
                        voiceZeroNs = time.nanoTime - time.framePosition * 1_000_000_000L / KaraokePcm.RATE
                        voiceTimestampAvailable = true
                    } else if (voiceZeroNs == 0L) voiceZeroNs = System.nanoTime() - voiceFrames * 1_000_000_000L / KaraokePcm.RATE
                    track()?.let { output ->
                        val timestamp = AudioTimestamp()
                        if (runCatching { output.getTimestamp(timestamp) }.getOrDefault(false) && timestamp.nanoTime >= startedNs && timestamp.framePosition > 0) {
                            playbackZeroNs = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / KaraokePcm.RATE
                            audibleFrames = timestamp.framePosition
                        }
                    }
                    if (SystemClock.elapsedRealtime() - checkedAt > 1000) {
                        checkedAt = SystemClock.elapsedRealtime()
                        if (works.root.usableSpace < 64L * 1024 * 1024) reportFailure("手机空间不足，已保留录音片段")
                    }
                }
            } catch (e: Exception) { if (capturing) reportFailure(e.message ?: "麦克风采集失败") }
            finally { voice.close(); runCatching { mic.stop() }; mic.close(); recorder = null }
        }, "karaoke-microphone").apply { start() }
        changed(); return id
    }

    fun appendBacking(bytes: ByteArray) {
        if (phase == "RECORDING" && capturing && !pending.offer(bytes)) reportFailure("音频写入繁忙，已停止并保留片段")
    }
    private fun reportFailure(message: String) {
        if (error.isNotEmpty()) return
        error = message; main.post { interrupt(message) }
    }
    fun finish(expectedId: String, reason: String = "", complete: Boolean = false) {
        require(expectedId == takeId) { "这段录音已失效，请刷新作品" }
        if (phase != "RECORDING") return
        capturing = false; phase = "FINALIZING"
        runCatching { recorder?.stop() }
        val id = takeId
        val record = JSONObject(meta.toString())
        changed()
        jobs.execute {
            try {
                reader?.join(3000); backingWriter?.join(3000)
                require(reader?.isAlive != true && backingWriter?.isAlive != true) { "音频文件仍在写入，请稍后恢复" }
                KaraokePcm.header(works.file(id, "voice.wav"), 1); KaraokePcm.header(works.backing(id), 2)
                val offset = if (voiceZeroNs != 0L && playbackZeroNs != 0L) KaraokePcm.offsetFrames(voiceZeroNs, playbackZeroNs) else 0L
                val available = KaraokePcm.frames(works.backing(id), 2)
                val settings = record.getJSONObject("settings")
                val start = settings.optLong("startMs"); val end = settings.optLong("endMs")
                val rangeFrames = if (end > start) ((end - start) * KaraokePcm.RATE / (1000.0 * settings.optDouble("speed", 1.0))).toLong() else Long.MAX_VALUE
                val length = min(rangeFrames, min(available, if (audibleFrames > 0) audibleFrames else available))
                record.put("frames", length).put("durationMs", length * 1000 / KaraokePcm.RATE)
                    .put("voiceOffsetFrames", offset).put("voiceFrames", voiceFrames).put("backingFrames", available)
                    .put("timestampAligned", voiceTimestampAvailable && playbackZeroNs != 0L)
                    .put("voiceZeroNs", voiceZeroNs).put("playbackZeroNs", playbackZeroNs).put("sampleRate", KaraokePcm.RATE)
                    .put("waveform", JSONArray(KaraokePcm.waveform(works.file(id, "voice.wav"), 1)))
                    .put("status", if (length > 0 && voiceFrames > 0) "PREVIEW" else "FAILED")
                    .put("complete", complete && reason.isEmpty()).put("interruption", reason.ifBlank { error })
                if (length == 0L) record.put("interruption", "未取得可用伴奏，请检查播放资源后重新录唱")
                works.put(record); meta = record; phase = record.getString("status")
            } catch (e: Exception) { error = e.message ?: "录音收尾失败"; phase = "FAILED" }
            main.post(changed)
        }
    }

    fun testMicrophone(start: Boolean) {
        if (!start) { if (phase == "TESTING") { capturing = false; runCatching { recorder?.stop() }; reader?.join(1000); require(reader?.isAlive != true) { "麦克风仍在收尾，请稍后重试" }; phase = "IDLE"; level = 0.0; changed() }; return }
        require(phase !in listOf("RECORDING", "FINALIZING", "RENDERING")) { "录唱中不能重新试音" }
        if (phase == "TESTING") return
        val mic = microphone(); mic.start(); recorder = mic; capturing = true; phase = "TESTING"; level = 0.0; clipped = false; error = ""
        reader = Thread({
            val data = ShortArray(960)
            try { while (capturing && phase == "TESTING") {
                val count = mic.read(data)
                if (count <= 0) break
                level = sqrt((0 until count).sumOf { data[it].toDouble() * data[it] } / count) / 32768.0
                clipped = (0 until count).any { abs(data[it].toInt()) >= 32760 }
            } } catch (e: Exception) { error = e.message ?: "试音输入已中断" }
            finally {
                runCatching { mic.stop() }; mic.close(); if (recorder === mic) recorder = null
                if (capturing && phase == "TESTING") main.post { capturing = false; phase = "IDLE"; if (error.isEmpty()) error = "麦克风未提供音频，请检查输入设备"; changed() }
            }
        }, "karaoke-test").apply { start() }
        changed()
    }

    fun render(id: String, backingGain: Double, voiceGain: Double, shiftMs: Int) {
        require(!busyRender && phase !in listOf("RECORDING", "FINALIZING", "TESTING")) { "请等待当前音频处理完成" }
        require(backingGain in 0.0..2.0 && voiceGain in 0.0..2.0 && shiftMs in -1000..1000)
        val record = works.get(id)
        require(record.optString("status") != "SAVED") { "已保存成品不会被覆盖" }
        busyRender = true; phase = "RENDERING"; takeId = id; error = ""; changed()
        jobs.execute {
            try {
                val partial = works.file(id, "preview.part")
                val frames = record.optLong("frames")
                require(frames > 0) { "没有可用的录音片段" }
                KaraokeEncoder.encode(works.backing(id), works.file(id, "voice.wav"), partial, frames,
                    record.optLong("voiceOffsetFrames") + shiftMs.toLong() * KaraokePcm.RATE / 1000, backingGain, voiceGain)
                KaraokeEncoder.validate(partial)
                Os.rename(partial.path, works.file(id, "preview.m4a").path)
                record.put("status", "PREVIEW").put("backingGain", backingGain).put("voiceGain", voiceGain).put("shiftMs", shiftMs).put("hasPreview", true)
                works.put(record); meta = record; phase = "PREVIEW"
            } catch (e: Exception) { phase = "PREVIEW"; error = e.message ?: "混音生成失败，原始录音已保留" }
            finally { busyRender = false; main.post(changed) }
        }
    }
    fun save(id: String, name: String): JSONObject {
        val record = works.get(id)
        if (record.optString("status") == "SAVED") return record
        require(!busyRender && record.optBoolean("hasPreview")) { "请先生成并试听混音" }
        require(name.isNotBlank() && name.length <= 120) { "作品名称不能为空或超过 120 字" }
        val partial = works.file(id, "mix.part")
        works.playable(id).copyTo(partial, overwrite = true)
        KaraokeEncoder.validate(partial)
        Os.rename(partial.path, works.file(id, "mix.m4a").path)
        record.put("status", "SAVED").put("name", name.trim()); works.put(record)
        works.backing(id).parentFile?.deleteRecursively()
        works.file(id, "preview.m4a").delete()
        if (id == takeId) { meta = record; phase = "SAVED" }
        main.post(changed); return record
    }
    fun discard(id: String) {
        require(id != takeId || phase !in listOf("RECORDING", "FINALIZING", "RENDERING")) { "请先结束当前录音或等待处理完成" }
        works.delete(id)
        if (id == takeId) { takeId = ""; phase = "IDLE"; meta = JSONObject(); error = ""; level = 0.0 }
        changed()
    }
    fun snapshot(): Map<String, Any?> = mapOf("phase" to phase, "takeId" to takeId, "level" to level, "clipped" to clipped,
        "freeBytes" to works.root.usableSpace, "recordedMs" to voiceFrames * 1000 / KaraokePcm.RATE, "error" to error,
        "input" to (recorder?.routeName ?: "系统默认麦克风"))
    fun close() {
        if (phase == "RECORDING") finish(takeId, "应用服务被系统结束，已保留片段")
        else testMicrophone(false)
        jobs.shutdown()
    }
}
