@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.mobile

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.media.*
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class KaraokeInstrumentedTest {
    private fun pcm(frequency: Double, count: Int, amplitude: Int = 8000, start: Int = 0, channels: Int = 2): ByteArray {
        val b = ByteBuffer.allocate(count * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { i -> repeat(channels) { b.putShort((sin(2 * PI * frequency * (start + i) / 48000) * amplitude).toInt().toShort()) } }
        return b.array()
    }
    private fun amplitude(samples: ShortArray, frequency: Double): Double {
        var a = 0.0; var b = 0.0
        samples.forEachIndexed { i, v -> a += v * sin(2 * PI * frequency * i / 48000); b += v * cos(2 * PI * frequency * i / 48000) }
        return hypot(a, b) * 2 / samples.size
    }
    private fun context(root: File) = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
    }
    private fun waitFor(description: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(description, condition())
    }
    @Test fun realEngineEncodesBothSignalsAndRecoversFilesWithoutDuplicateWorks() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "audio-test-${System.nanoTime()}").apply { mkdirs() }
        val testContext = context(root)
        val tone = object : VoiceCapture {
            var on = false; var frames = 0L; var origin = 0L
            override val isRecording get() = on
            override val routeName = "instrumentation-only controlled voice input"
            override fun start() { origin = System.nanoTime(); on = true }
            override fun read(samples: ShortArray): Int { Thread.sleep(20); if (!on) return 0; samples.indices.forEach { samples[it] = (sin(2 * PI * 880 * (frames + it) / 48000) * 10000).toInt().toShort() }; frames += samples.size; return samples.size }
            override fun timestamp(out: AudioTimestamp): Boolean { out.framePosition = frames; out.nanoTime = origin + frames * 1_000_000_000L / 48000; return true }
            override fun stop() { on = false }
            override fun close() { on = false }
        }
        val engine = KaraokeEngine(testContext, { null }, {}, { fail(it) }, { tone })
        try {
            val id = engine.start(JSONObject().put("id", "fixture").put("title", "controlled two-source test"), "isolated", JSONObject().put("speed", 1))
            assertThrows(IllegalArgumentException::class.java) { engine.start(JSONObject(), "other", JSONObject()) }
            assertThrows(IllegalArgumentException::class.java) { engine.finish("stale-take-id") }
            assertThrows(IllegalArgumentException::class.java) { engine.render(id, 1.0, 1.0, 0) }
            repeat(60) { i -> engine.appendBacking(pcm(220.0, 960, start = i * 960)); Thread.sleep(20) }
            engine.finish(id, "", true)
            waitFor("capture finalized") { engine.phase == "PREVIEW" }
            engine.render(id, .6, 1.0, 0)
            waitFor("native AAC encoder completed") { engine.phase != "RENDERING" }
            assertTrue(engine.works.get(id).optBoolean("hasPreview"))
            engine.render(id, .6, 1.0, 100)
            waitFor("delayed voice preview") { engine.phase != "RENDERING" }
            val delayed = decode(engine.works.playable(id))
            assertTrue("positive alignment adds silence before voice", amplitude(delayed.copyOfRange(1500, 4000), 880.0) < 500)
            assertTrue("backing starts independently of voice alignment", amplitude(delayed.copyOfRange(1500, 4000), 220.0) > 1500)
            assertTrue("voice resumes after the requested alignment offset", amplitude(delayed.copyOfRange(7000, 18000), 880.0) > 3000)
            val saved = engine.save(id, "controlled mix")
            assertEquals("SAVED", saved.getString("status")); assertEquals(id, engine.save(id, "repeat").getString("id"))
            assertEquals(1, engine.works.all().size)
            engine.works.playable(id).copyTo(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "controlled-mix.m4a"), overwrite = true)
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val artifactName = "controlled-mix-${System.currentTimeMillis()}.m4a"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, artifactName)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/SingVerse-tests")
            }
            val uri = target.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            target.contentResolver.openOutputStream(uri)!!.use { output -> engine.works.playable(id).inputStream().use { it.copyTo(output) } }
            val exported = target.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertTrue("controlled mix artifact exported", exported.size > 1000)
            android.util.Log.i("SingVerseAudioTest", "artifact=/sdcard/Music/SingVerse-tests/$artifactName")
            val decoded = decode(engine.works.playable(id))
            assertTrue("backing tone is present", amplitude(decoded, 220.0) > 1500)
            assertTrue("voice tone is present", amplitude(decoded, 880.0) > 3000)
            assertTrue("AAC padding is within four codec blocks", abs(decoded.size - saved.getLong("frames")) <= 4096)
            assertTrue(decoded.all { it.toInt() in -32767..32767 })
            engine.works.file(id, "mix.m4a").delete(); engine.works.recover()
            assertEquals("MISSING", engine.works.get(id).getString("status"))
            engine.discard(id); assertEquals(0, engine.works.all().size); assertEquals("IDLE", engine.phase); assertEquals("", engine.takeId)
            val interrupted = java.util.UUID.randomUUID().toString()
            KaraokePcm.create(engine.works.file(interrupted, "voice.wav")).use { it.write(pcm(880.0, 4800, channels = 1)) }
            KaraokePcm.create(engine.works.backing(interrupted)).use { it.write(pcm(220.0, 4800)) }
            engine.works.put(JSONObject().put("id", interrupted).put("status", "RECORDING").put("settings", JSONObject()))
            engine.works.recover()
            val recovered = engine.works.get(interrupted)
            assertEquals("PREVIEW", recovered.getString("status")); assertFalse(recovered.optBoolean("complete"))
            assertEquals(4800L, recovered.getLong("frames")); assertEquals(100L, recovered.getLong("durationMs"))
            assertTrue(recovered.getJSONArray("waveform").length() > 0)
            assertEquals("RIFF", String(engine.works.file(interrupted, "voice.wav").readBytes(), 0, 4))
        } finally { engine.close(); engine.works.close(); root.deleteRecursively() }
    }
    @Test fun microphoneStartFailureCannotBecomeARecordedOrSavedTake() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "failed-audio-test-${System.nanoTime()}").apply { mkdirs() }
        var closed = false
        val engine = KaraokeEngine(context(root), { null }, {}, {}, { object : VoiceCapture {
            override val isRecording = false
            override val routeName = "test-only failed input"
            override fun start() { throw IOException("input unavailable") }
            override fun read(samples: ShortArray) = 0
            override fun timestamp(out: AudioTimestamp) = false
            override fun stop() = Unit
            override fun close() { closed = true }
        } })
        try {
            assertThrows(IOException::class.java) { engine.start(JSONObject().put("id", "failure-fixture"), "isolated", JSONObject()) }
            assertEquals("FAILED", engine.phase); assertTrue(closed)
            assertEquals("FAILED", engine.works.get(engine.takeId).getString("status"))
            assertThrows(IllegalArgumentException::class.java) { engine.save(engine.takeId, "must not save") }
        } finally { engine.close(); engine.works.close(); root.deleteRecursively() }
    }
    @Test fun capturedAccompanimentIsAfterRealSonicPitchAndSpeedProcessing() {
        val captured = ByteArrayOutputStream()
        val chain = KaraokeAudioChain { captured.write(it) }
        chain.captureEnabled = true
        chain.applyPlaybackParameters(PlaybackParameters(.8f, 2.0.pow(2.0 / 12).toFloat()))
        var format = AudioProcessor.AudioFormat(48000, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        var bytes = pcm(220.0, 48000)
        for (processor in chain.audioProcessors) {
            format = processor.configure(format); processor.flush()
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            val out = ByteArrayOutputStream()
            val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
            processor.queueInput(input)
            var buffer = processor.output
            if (buffer.hasRemaining()) { val data = ByteArray(buffer.remaining()); buffer.get(data); out.write(data) }
            processor.queueEndOfStream()
            while (!processor.isEnded) { buffer = processor.output; val data = ByteArray(buffer.remaining()); buffer.get(data); out.write(data) }
            bytes = out.toByteArray()
        }
        assertArrayEquals(bytes, captured.toByteArray())
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(bytes.size / 4) { val left = b.short; b.short; left }
        assertTrue("0.8x should lengthen the captured accompaniment", mono.size in 58000..62000)
        assertTrue("post-speed media duration maps back to one original second", chain.getMediaDuration(mono.size * 1_000_000L / 48000) in 950000L..1050000L)
        assertTrue("captured pitch follows the +2-semitone setting", amplitude(mono, 220 * 2.0.pow(2.0/12)) > 1000)
    }
    private fun decode(file: File): ShortArray {
        val extractor = MediaExtractor(); extractor.setDataSource(file.path); extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE)); assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0); decoder.start()
        val result = ArrayList<Short>(); var inputDone = false; var done = false
        val info = MediaCodec.BufferInfo(); val end = System.currentTimeMillis() + 15000
        try {
            while (!done && System.currentTimeMillis() < end) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) { val buffer = decoder.getInputBuffer(index)!!; val size = extractor.readSampleData(buffer, 0); inputDone = size < 0; decoder.queueInputBuffer(index, 0, max(0,size), if (inputDone) 0 else extractor.sampleTime, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0); if (!inputDone) extractor.advance() }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index >= 0) {
                    val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    while (buffer.remaining() >= 4) { result.add(buffer.short); buffer.short }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("AAC decoding finished", done)
        } finally { decoder.stop(); decoder.release(); extractor.release() }
        return result.toShortArray()
    }
}
