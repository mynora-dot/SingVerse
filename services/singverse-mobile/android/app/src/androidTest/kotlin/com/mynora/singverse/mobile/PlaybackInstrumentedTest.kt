@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.media.AudioTimestamp
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.hypot

/** Runs the real service/player against loopback HTTP, without a NAS or a real song. */
@RunWith(AndroidJUnit4::class)
class PlaybackInstrumentedTest {
    @Test fun preparingIsPacedAndSlowCancellationDoesNotDelayTheNextSong() {
        val polls = Collections.synchronizedList(mutableListOf<Long>())
        val cancelStarted = CountDownLatch(1); val releaseCancel = CountDownLatch(1)
        val nextOpened = CountDownLatch(1)
        val server = HttpFixture { request ->
            when {
                request.path == "/api/v1/auth/csrf" -> Reply("{\"token\":\"test-token\"}")
                request.path.endsWith("/playback/first") -> Reply(pending("first-lease"))
                request.path.endsWith("/playback/second") -> { nextOpened.countDown(); Reply(pending("second-lease")) }
                request.path.endsWith("/first-lease/cancel") -> {
                    cancelStarted.countDown(); releaseCancel.await(8, TimeUnit.SECONDS); Reply("{}")
                }
                request.path.endsWith("/first-lease") -> { polls.add(SystemClock.elapsedRealtime()); Reply(pending("first-lease")) }
                else -> Reply(pending("second-lease"))
            }
        }
        try { withService(server) { service ->
            main { service.open(song("first"), "isolated") }
            waitFor("three preparation polls") { polls.size >= 3 }
            val times = synchronized(polls) { polls.take(3) }
            assertTrue("preparation must not poll every 100ms: $times", times.zipWithNext().all { (a, b) -> b - a >= 700 })
            main { service.open(song("second"), "isolated") }
            assertTrue("old cleanup really entered its slow request", cancelStarted.await(2, TimeUnit.SECONDS))
            assertTrue("new song must start before old cancel finishes", nextOpened.await(1, TimeUnit.SECONDS))
            main { assertEquals("second", JSONObject(service.snapshot()["song"].toString()).getString("id")) }
            releaseCancel.countDown()
        } } finally { releaseCancel.countDown(); server.close() }
    }

    @Test fun switchingSongsCancelsAnInFlightNegotiationBeforeItsResponseArrives() {
        val firstStarted = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val server = HttpFixture { request ->
            when {
                request.path.endsWith("/csrf") -> Reply("{\"token\":\"test-token\"}")
                request.path.endsWith("/playback/first") -> {
                    firstStarted.countDown(); releaseFirst.await(8, TimeUnit.SECONDS); Reply(pending("late-first"))
                }
                request.path.endsWith("/playback/second") -> { secondStarted.countDown(); Reply(pending("current-second")) }
                else -> Reply(pending("current-second"))
            }
        }
        try { withService(server) { service ->
            main { service.open(song("first"), "isolated") }
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            main { service.open(song("second"), "isolated") }
            assertTrue("new request runs while first server response is still blocked", secondStarted.await(1, TimeUnit.SECONDS))
            releaseFirst.countDown()
            main { assertEquals("second", JSONObject(service.snapshot()["song"].toString()).getString("id")) }
        } } finally { releaseFirst.countDown(); server.close() }
    }

    @Test fun actualUnsupportedMediaTriggersOneCompatibleRetryAndBecomesPlayable() {
        val attempts = Collections.synchronizedList(mutableListOf<JSONObject>())
        val mediaStarted = CountDownLatch(1); val releaseMedia = CountDownLatch(1)
        val wav = wave()
        val server = HttpFixture { request ->
            when {
                request.path.endsWith("/csrf") -> Reply("{\"token\":\"test-token\"}")
                request.path.endsWith("/playback/unsupported") -> {
                    val options = JSONObject(request.body); attempts.add(options)
                    Reply(JSONObject().put("id", "lease-${attempts.size}").put("status", "READY").put("mode", "DIRECT")
                        .put("url", if (options.getBoolean("force")) "/api/v1/test/good.wav" else "/api/v1/test/unsupported.bin").toString())
                }
                request.path.endsWith("/good.wav") -> Reply(wav, "application/octet-stream")
                request.path.endsWith("/unsupported.bin") -> {
                    mediaStarted.countDown(); releaseMedia.await(8, TimeUnit.SECONDS)
                    Reply(ByteArray(4096) { 0x55 }, "application/octet-stream")
                }
                else -> Reply("{}")
            }
        }
        try { withService(server) { service ->
            main { service.open(song("unsupported"), "isolated") }
            assertTrue(mediaStarted.await(3, TimeUnit.SECONDS))
            // Lock-screen controllers operate Player directly, bypassing the Flutter pause command.
            main { service.player.pause() }
            releaseMedia.countDown()
            waitFor("real Media3 parser error retried and decoded controlled WAV", 15_000) {
                var ready = false
                main { ready = service.player.playbackState == Player.STATE_READY }
                attempts.size == 2 && ready
            }
            assertFalse(attempts[0].getBoolean("force")); assertTrue(attempts[1].getBoolean("force"))
            main { assertEquals("PAUSED", service.snapshot()["state"]); assertFalse(service.player.playWhenReady) }
            Thread.sleep(1000)
            assertEquals("compatibility retry is bounded to one", 2, attempts.size)
        } } finally { releaseMedia.countDown(); server.close() }
    }

    @Test fun recordingKeepsTheMergedBackingSourceAndItsTrackMetadata() {
        val original = wave(220.0); val backing = wave(660.0)
        val server = HttpFixture { request -> when {
            request.path.endsWith("/csrf") -> Reply("{\"token\":\"test-token\"}")
            request.path.contains("/playback/") -> Reply(JSONObject().put("id", "merged-lease").put("status", "READY").put("mode", "DIRECT")
                .put("url", "/api/v1/test/original.wav").put("backingUrl", "/api/v1/test/backing.wav").toString())
            request.path.endsWith("/original.wav") -> Reply(original, "audio/wav")
            request.path.endsWith("/backing.wav") -> Reply(backing, "audio/wav")
            else -> Reply("{}")
        } }
        try { withService(server) { service ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val target = instrumentation.targetContext
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${target.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
            val root = File(target.cacheDir, "merged-recording-${UUID.randomUUID()}").apply { mkdirs() }
            val isolated = object : ContextWrapper(target) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
                override fun getDatabasePath(name: String) = File(root, name)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
                override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, handler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, handler)
            }
            val chain = PlaybackService::class.java.getDeclaredField("chain").apply { isAccessible = true }.get(service) as KaraokeAudioChain
            val voice = object : VoiceCapture {
                @Volatile var on = false
                override val isRecording get() = on
                override val routeName = "instrumentation-only silence"
                override fun start() { on = true }
                override fun read(samples: ShortArray): Int { Thread.sleep(20); if (!on) return 0; samples.fill(0); return samples.size }
                override fun timestamp(out: AudioTimestamp) = false
                override fun stop() { on = false }
                override fun close() { on = false }
            }
            val engine = KaraokeEngine(isolated, { chain.audioTrack }, {}, {}, { voice })
            try {
                main {
                    service.karaoke.close(); service.karaoke.works.close()
                    PlaybackService::class.java.getDeclaredField("karaoke").apply { isAccessible = true }.set(service, engine)
                    service.open(song("merged"), "isolated", autoplay = false)
                }
                waitReady(service, "original source ready")
                main { service.command("useRole", "backing") }
                waitFor("merged backing is ready") { var ready = false; main { ready = service.player.playbackState == Player.STATE_READY && service.snapshot()["audioRole"] == "backing" }; ready }
                main { service.beginRecording() }
                waitFor("actual playback PCM was captured after recording restart", 15_000) {
                    engine.phase == "RECORDING" && engine.takeId.isNotBlank() && engine.works.backing(engine.takeId).length() >= 48000 * 2
                }
                main { service.finishRecording("controlled test") }
                waitFor("recording finalized") { engine.phase == "PREVIEW" }
                val bytes = engine.works.backing(engine.takeId).readBytes()
                val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
                val samples = ShortArray(minOf((bytes.size - 44) / 4, 24000))
                samples.indices.forEach { samples[it] = pcm.short; pcm.short }
                fun amplitude(frequency: Double): Double {
                    var real = 0.0; var imaginary = 0.0
                    samples.forEachIndexed { i, sample -> real += sample * sin(2 * PI * frequency * i / 48000); imaginary += sample * cos(2 * PI * frequency * i / 48000) }
                    return hypot(real, imaginary) * 2 / samples.size
                }
                assertTrue("recorded PCM must retain the 660Hz backing, not the original 220Hz source", amplitude(660.0) > 500 && amplitude(660.0) > amplitude(220.0) * 10)
                val tracks = engine.works.get(engine.takeId).getJSONObject("settings").getJSONArray("tracks")
                assertTrue("track snapshot must be saved before stop clears currentTracks", tracks.length() > 0)
            } finally { main { service.command("pause", null) }; engine.close(); engine.works.close(); root.deleteRecursively() }
        } } finally { server.close() }
    }

    @Test fun eventHlsSeeksInsideItsWindowWithoutAnotherNegotiationAndKeepsAuth() {
        val audio = InstrumentationRegistry.getInstrumentation().context.assets.open("controlled-tone.aac").use { it.readBytes() }
        val attempts = Collections.synchronizedList(mutableListOf<JSONObject>())
        val mediaCookies = Collections.synchronizedList(mutableListOf<String>())
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2.02,\npart0.aac\n#EXTINF:2.02,\npart1.aac\n#EXTINF:2.02,\npart2.aac\n#EXT-X-ENDLIST\n"
        val server = HttpFixture { request -> when {
            request.path.endsWith("/csrf") -> Reply("{\"token\":\"test-token\"}")
            request.path.contains("/playback/") -> {
                val options = JSONObject(request.body); attempts.add(options)
                Reply(JSONObject().put("id", "hls-${attempts.size}").put("status", "READY").put("mode", "HLS")
                    .put("offset", options.getDouble("start")).put("url", "/api/v1/test/index.m3u8").toString())
            }
            request.path.endsWith(".m3u8") -> { mediaCookies.add(request.cookie); Reply(playlist.toByteArray(), "application/octet-stream") }
            request.path.endsWith(".aac") -> { mediaCookies.add(request.cookie); Reply(audio, "application/octet-stream") }
            else -> Reply("{}")
        } }
        try { withService(server) { service ->
            main { service.open(song("seek"), "isolated", position = 30_000, compatible = true, autoplay = false) }
            waitFor("real EVENT HLS has a seekable timeline", 15_000) {
                var ready = false; main { ready = service.player.playbackState == Player.STATE_READY && service.player.isCurrentMediaItemSeekable }; ready
            }
            main { service.command("seek", 33_000L); assertEquals(3000L, service.player.currentPosition); assertFalse(service.player.playWhenReady) }
            assertEquals("inside-window seek must retain the existing session", 1, attempts.size)
            assertTrue(mediaCookies.isNotEmpty()); assertTrue("every playlist and segment uses current NAS cookie", mediaCookies.all { it.contains("singverse_session=fixture") })
            main { service.command("seek", 29_000L) }
            waitFor("before-offset seek renegotiated") { attempts.size == 2 }
            assertEquals(29.0, attempts[1].getDouble("start"), 0.001); assertTrue(attempts[1].getBoolean("force"))
            main { assertFalse(service.player.playWhenReady) }
        } } finally { server.close() }
    }

    private fun song(id: String) = JSONObject().put("id", id).put("title", "Controlled $id")
    private fun pending(id: String) = JSONObject().put("id", id).put("mode", "HLS").put("status", "PREPARING").toString()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun localSavedWorkPlaybackNeverCreatesANasHistoryAttempt() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpFixture { request ->
            if (request.path == "/api/v1/mobile/playback-attempts") attempts.incrementAndGet()
            if (request.path.endsWith("/csrf")) Reply("{\"token\":\"test-token\"}") else Reply("{}")
        }
        try { withService(server) { service ->
            val id = UUID.randomUUID().toString()
            try {
                main {
                    service.karaoke.works.file(id, "mix.m4a").writeBytes(wave())
                    service.karaoke.works.put(JSONObject().put("id", id).put("status", "SAVED").put("name", "fixture work")
                        .put("song", song("not-a-nas-play")).put("durationMs", 5000))
                    service.playWork(id)
                }
                waitFor("local saved work actually plays") { var playing = false; main { playing = service.player.isPlaying }; playing }
                Thread.sleep(1200)
                assertEquals("works are not NAS song listening", 0, attempts.get())
                main { service.command("stop", null) }
            } finally { main { service.karaoke.works.delete(id) } }
        } } finally { server.close() }
    }

    @Test fun realPlaybackReportsDoNotCountSeekOrPauseAndFullLoopsCreateNewAttempts() {
        val events = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val grants = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bytes = wave()
        val server = HttpFixture { request -> when {
            request.path.endsWith("/csrf") -> Reply("{\"token\":\"test-token\"}")
            request.path == "/api/v1/mobile/playback-attempts" -> {
                val id = UUID.randomUUID().toString(); grants.add(id)
                Reply(JSONObject().put("attemptId", id).put("writeToken", "fixture-token").toString())
            }
            request.path.endsWith("/events") -> {
                events.add(JSONObject(request.body)); Reply("{}")
            }
            request.path.contains("/playback/") -> Reply(JSONObject().put("status", "READY").put("mode", "DIRECT")
                .put("url", "/api/v1/test/tone.wav").toString())
            request.path.endsWith("/tone.wav") -> Reply(bytes, "audio/wav")
            else -> Reply("{}")
        } }
        try { withService(server) { service ->
            main { service.open(song("telemetry"), "isolated", autoplay = false) }
            waitReady(service, "paused media ready")
            waitFor("paused grant registered") { events.any { it.optString("state") == "PAUSED" } }
            main { service.command("play", null) }
            waitFor("actual PLAYING reported") { events.any { it.optString("state") == "PLAYING" } }
            Thread.sleep(1200)
            main { service.command("pause", null) }
            waitFor("pause closes actual listening interval") { events.lastOrNull()?.optString("state") == "PAUSED" }
            val paused = events.last().getLong("actualPlayedMs")
            val lastActivityAt = events.last().getLong("lastActivityAt")
            assertTrue("real playback is counted", paused in 500..3000)
            Thread.sleep(1200)
            main { service.command("seek", 4000L) }
            main { service.command("pause", null) }
            assertEquals("paused seek cannot refresh real activity", lastActivityAt, events.last().getLong("lastActivityAt"))
            main { service.command("play", null) }
            waitFor("seek resumes without adding its 4-second position jump") { events.lastOrNull()?.optString("state") == "PLAYING" }
            assertTrue("pause and seek are excluded", events.last().getLong("actualPlayedMs") - paused in 0..350)
            waitFor("seeked song completed") { events.lastOrNull()?.optString("state") == "COMPLETED" }
            assertEquals("seek remains one attempt", 1, grants.size)
            assertTrue(events.last().getLong("actualPlayedMs") < 4000)
            main { service.command("loop", true); service.open(song("telemetry"), "isolated") }
            waitFor("whole-song repeat has a fresh grant", 15000) { grants.size >= 3 }
            assertEquals("loop grants are distinct", grants.size, grants.toSet().size)
            main { service.command("stop", null) }
            waitFor("stop releases current playback lease") { events.lastOrNull()?.optString("state") == "STOPPED" }
        } } finally { server.close() }
    }

    private fun waitReady(service: PlaybackService, message: String) {
        var diagnostic = ""
        try { waitFor(message, 15_000) { var ready = false
            main { ready = service.player.playbackState == Player.STATE_READY
                diagnostic = "state=${service.snapshot()["state"]}, message=${service.snapshot()["message"]}, playerError=${service.player.playerError}"
            }; ready
        } } catch (failure: AssertionError) { throw AssertionError("$message: $diagnostic", failure) }
    }
    private fun waitFor(message: String, timeout: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue(message, condition())
    }
    private fun withService(server: HttpFixture, block: (PlaybackService) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertTrue("only the isolated .e2e application may run service tests", target.packageName.endsWith(".e2e"))
        assertNull(PlaybackService.current)
        val preferences = "playback-fixture-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferences, Context.MODE_PRIVATE)
        }
        var activity: android.app.Activity? = null
        val singleton = NasTransport::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousTransport = singleton.get(null)
        try {
            // Bind the service AND its real Media3 HTTP factory to the isolated transport.
            // MainActivity starts the service from onCreate, so bind before launching it.
            singleton.set(null, NasTransport(isolated).apply { configure(server.origin) })
            // Flutter may repaint continuously; startActivitySync incorrectly waits for global
            // queue idleness. Require the actual foreground lifecycle instead.
            main { target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            waitFor("test activity resumed", 30_000) {
                main { activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is MainActivity } }
                activity != null
            }
            main { target.startService(Intent(target, PlaybackService::class.java)) }
            waitFor("service started") { PlaybackService.current != null }
            val service = PlaybackService.current!!
            block(service)
        } finally {
            main {
                target.stopService(Intent(target, PlaybackService::class.java))
                Stage.values().flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                    .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            }
            waitFor("service stopped") { PlaybackService.current == null }
            singleton.set(null, previousTransport)
            target.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun wave(frequency: Double = 440.0): ByteArray {
        val frames = 48000 * 5
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(48000); putInt(96000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            for (i in 0 until frames) putShort((sin(2 * PI * frequency * i / 48000) * 2000).toInt().toShort())
        }.array()
    }
    private data class Received(val path: String, val body: String, val cookie: String)
    private data class Reply(val bytes: ByteArray, val type: String) {
        constructor(json: String) : this(json.toByteArray(), "application/json")
    }
    private class HttpFixture(private val respond: (Received) -> Reply) : AutoCloseable {
        private val server = ServerSocket(0)
        private val acceptor = Executors.newSingleThreadExecutor()
        private val clients = Executors.newFixedThreadPool(6)
        private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
        val origin = "http://127.0.0.1:${server.localPort}"
        init { acceptor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                clients.execute { try { socket.use {
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val out = ByteArrayOutputStream()
                        while (true) { val value = input.read(); if (value < 0 || value == 10) break; if (value != 13) out.write(value) }
                        return out.toString("UTF-8")
                    }
                    val path = line().split(' ').getOrNull(1).orEmpty()
                    var length = 0; var cookie = ""
                    while (true) { val header = line(); if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        if (header.startsWith("Cookie:", true)) cookie = header.substringAfter(':').trim()
                    }
                    val body = ByteArray(length); var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); if (count < 0) break; offset += count }
                    val reply = respond(Received(path, String(body, Charsets.UTF_8), cookie))
                    android.util.Log.i("PlaybackFixture", "served $path (${reply.bytes.size} bytes)")
                    socket.getOutputStream().apply {
                        val credential = if (path.endsWith("/csrf")) "Set-Cookie: singverse_session=fixture; Path=/\r\n" else ""
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${reply.type}\r\nContent-Length: ${reply.bytes.size}\r\n${credential}Connection: close\r\n\r\n".toByteArray())
                        write(reply.bytes); flush()
                    }
                } } catch (_: java.io.IOException) { } finally { sockets.remove(socket) } }
            }
        } }
        override fun close() {
            server.close(); synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
            acceptor.shutdownNow(); clients.shutdownNow()
        }
    }
}
