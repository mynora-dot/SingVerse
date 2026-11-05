package com.mynora.singverse.tv

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic media and an isolated approximation of the existing session/command HTTP contract. */
internal class TvPlaybackFixture(videoBytes: ByteArray) {
    /** Set before START only in the generated-accompaniment case; default songs stay unchanged. */
    @Volatile var generatedBacking = false
    val unavailableResourceSongs = ConcurrentHashMap.newKeySet<String>()
    @Volatile var holdNextResponse = false
    @Volatile var heldNextResponse: String? = null
        private set
    private val nextResponseGate = CountDownLatch(1)
    val resourceRequests = CopyOnWriteArrayList<JSONObject>()
    val unconfirmedResourceRequests = CopyOnWriteArrayList<JSONObject>()
    val playbackEvents = CopyOnWriteArrayList<JSONObject>()
    private val mediaBytes = mapOf(
        AUDIO_URL to (wave() to "audio/wav"),
        VIDEO_URL to (videoBytes to "video/mp4")
    )
    private val queue = mutableListOf<JSONObject>()
    private val commands = mutableListOf<JSONObject>()
    private var current: JSONObject? = null
    private var revision = 1L
    private var lastEvent = -1L

    fun media(path: String): Pair<ByteArray, String>? = mediaBytes[path]
        ?: if (path == GENERATED_BACKING_URL) mediaBytes[AUDIO_URL] else null

    /** Pause only the HTTP response, not server state or concurrent snapshot/media requests. */
    fun beforeSend(path: String, body: JSONObject, response: String) {
        if (holdNextResponse && path.endsWith("/commands") && body.optString("action") == "NEXT") {
            heldNextResponse = response
            check(nextResponseGate.await(30, TimeUnit.SECONDS)) { "测试NEXT响应未释放" }
        }
    }

    fun releaseNextResponse() { nextResponseGate.countDown() }

    /** Simulates another authorized controller; the app learns this state through real HTTP refresh. */
    @Synchronized fun remoteNext() { next(); revision++ }

    @Synchronized fun snapshot(): JSONObject = TvUiFixture.snapshot()
        .put("queue", JSONArray(queue.map { JSONObject(it.toString()) }))
        .put("commands", JSONArray(commands.map { JSONObject(it.toString()) }))
        .put("revision", revision).put("stateRevision", revision)
        .also { result -> current?.let { result.put("playback", JSONObject(it.toString())) } }

    /** Null delegates unrelated endpoints, including real dynamic invitation rendering, to TvUiFixture. */
    @Synchronized fun response(path: String, body: JSONObject): String? = when {
        path == "/api/v1/tv/playback-resources" -> {
            resourceRequests.add(JSONObject(body.toString()))
            if (current?.optBoolean("confirmed") != true) unconfirmedResourceRequests.add(JSONObject(body.toString()))
            resource(body.getString("songId"), body.optString("audioRole")).toString()
        }
        path.endsWith("/playback-events") -> {
            playbackEvents.add(JSONObject(body.toString()))
            if (body.has("commandId")) commands.removeAll { it.optString("id") == body.getString("commandId") }
            val play = current
            if (play != null && body.optString("attemptId") == play.optString("attemptId") && body.optLong("eventSeq", -1) > lastEvent) {
                lastEvent = body.getLong("eventSeq")
                play.put("state", body.optString("state")).put("positionMs", body.optLong("positionMs"))
                if (body.optString("state") == "PLAYING") queue.firstOrNull { it.optString("id") == play.optString("itemId") }?.put("status", "PLAYING")
            }
            revision++
            snapshot().toString()
        }
        path.endsWith("/commands") -> {
            when (body.getString("action")) {
                "ADD" -> {
                    val song = songs().first { it.optString("id") == body.getString("songId") }
                    queue.add(JSONObject().put("id", "player-test-queue-${queue.size}")
                        .put("songId", song.getString("id")).put("song", song).put("status", "WAITING").put("ownerName", "播放器验收测试"))
                }
                "START" -> start()
                "NEXT" -> {
                    next()
                }
                "RESTART" -> {
                    current?.put("attemptId", UUID.randomUUID().toString())?.put("positionMs", 0)
                    lastEvent = -1
                    enqueue("RESTART")
                }
                "PAUSE", "RESUME", "STOP" -> enqueue(body.getString("action"))
                "CLEAR" -> queue.filter { it.optString("status") == "WAITING" }.forEach { it.put("status", "CANCELLED") }
            }
            revision++
            snapshot().toString()
        }
        path.endsWith("/snapshot") || path.endsWith("/recover") || path == "/api/v1/tv/sessions" -> snapshot().toString()
        path == "/api/v1/tv/home" -> JSONObject().put("recentSongs", JSONArray(songs())).put("totalSongs", songs().size)
            .put("server", JSONObject().put("name", "播放器验收测试服务"))
            .put("appearance", TvUiFixture.appearance()).put("featuredPlaylists", JSONArray()).toString()
        path.endsWith("/text-tracks") -> {
            val empty = path.substringBeforeLast('/').endsWith("test-song-3")
            JSONObject().put("items", if (empty) JSONArray() else JSONArray().put(JSONObject()
                .put("id", if (path.contains("test-song-1/")) "metadata-lyrics" else "lrc")
                .put("format", "lrc").put("text", LYRICS)))
                .put("warnings", JSONArray()).toString()
        }
        path.startsWith("/api/v1/tv/audio-roles/songs/") -> roles(path.substringAfterLast('/')).toString()
        path == "/api/v1/tv/library/songs" -> JSONObject().put("items", JSONArray(songs())).put("totalElements", songs().size).toString()
        path == "/api/v1/tv/library/classification-tags" -> JSONArray(classificationTags()).toString()
        path.matches(Regex("/api/v1/tv/library/songs/test-song-\\d+")) -> songs().first { it.optString("id") == path.substringAfterLast('/') }.toString()
        else -> null
    }

    private fun start() {
        val existing = queue.firstOrNull { it.optString("status") == "HANDOFF" }
        if (existing != null && current?.optString("itemId") == existing.optString("id")) current?.put("confirmed", true)
        else handoff(true)
        if (current != null) enqueue("LOAD")
    }

    private fun next() {
        commands.clear()
        queue.filter { it.optString("status") in listOf("PLAYING", "HANDOFF") }.forEach { it.put("status", "SKIPPED") }
        enqueue("STOP")
        handoff(false)
    }

    private fun handoff(confirmed: Boolean) {
        val candidate = queue.firstOrNull { it.optString("status") == "WAITING" }
        current = candidate?.let {
            it.put("status", "HANDOFF")
            lastEvent = -1
            JSONObject().put("itemId", it.getString("id")).put("songId", it.getString("songId"))
                .put("attemptId", UUID.randomUUID().toString()).put("state", "PREPARING")
                .put("confirmed", confirmed).put("eventSeq", -1).put("positionMs", 0)
        }
    }

    private fun enqueue(action: String) {
        commands.add(JSONObject().put("id", UUID.randomUUID().toString()).put("action", action)
            .also { command -> current?.let { command.put("playback", JSONObject(it.toString())) } })
    }

    private fun resource(song: String, audioRole: String): JSONObject {
        if (song in unavailableResourceSongs) return JSONObject().put("status", "FAILED").put("state", "FAILED")
            .put("revision", "player-test-v1").put("error", "测试资源暂不可用")
        val video = song == "test-song-2"
        val streams = streams(song)
        return JSONObject().put("status", "READY").put("mode", "DIRECT").put("revision", "player-test-v1")
            .put("url", if (video) VIDEO_URL else AUDIO_URL).put("offset", 0)
            .put("execution", JSONObject().put("media", JSONObject().put("duration", DURATION_MS / 1000.0)
                .put("bitrate", if (video) 174191 else 768000)
                .put("container", if (video) "mp4" else "wav").put("streams", streams)))
            .put("audioRoles", roles(song))
            .also { if (generatedBacking && song == "test-song-1" && audioRole == "backing") it.put("backingUrl", GENERATED_BACKING_URL) }
    }

    private fun streams(song: String): JSONArray = JSONArray().apply {
        if (song == "test-song-2") {
            put(JSONObject().put("id", "stream:0").put("index", 0).put("kind", "video").put("codec", "h264").put("width", 320).put("height", 180))
            put(audioStream("stream:1", "aac", "Test original"))
            put(audioStream("stream:2", "aac", "Test accompaniment"))
        } else put(audioStream("stream:0", "pcm_s16le", "Test stereo original"))
    }

    private fun audioStream(id: String, codec: String, title: String) = JSONObject().put("id", id).put("index", id.substringAfter(':').toInt()).put("kind", "audio")
        .put("codec", codec).put("title", title).put("channels", 2)

    private fun roles(song: String): JSONObject = JSONObject().put("revision", 1).put("state", "CONFIRMED")
        .put("originalId", if (song == "test-song-2") "stream:1" else "stream:0")
        .put("streams", JSONArray(streams(song).objects().filter { it.optString("kind") == "audio" }))
        .also {
            if (song == "test-song-2") it.put("backingId", "stream:2")
            if (generatedBacking && song == "test-song-1") it.put("backingResource", JSONObject()
                .put("jobId", "test-generated-backing").put("mediaType", "audio/wav"))
        }

    companion object {
        const val DURATION_MS = 40000L
        const val AUDIO_URL = "/api/v1/tv/test-media/synthetic-stereo.wav"
        const val GENERATED_BACKING_URL = "/api/v1/tv/test-media/synthetic-generated-backing.wav"
        const val VIDEO_URL = "/api/v1/tv/test-media/synthetic-dual-audio.mp4"
        const val VIDEO_ASSET = "player-fixtures/synthetic-dual-audio.mp4"
        const val LYRICS = "[00:02.00]测试歌词第一句\n[00:04.00]测试歌词第二句\n[00:08.00]测试歌词第三句\n[00:12.00]测试歌词第四句\n[00:16.00]测试歌词第五句\n[00:20.00]测试歌词第六句\n[00:24.00]测试歌词第七句\n[00:28.00]测试歌词第八句\n[00:32.00]测试歌词第九句\n[00:36.00]测试歌词结束句"

        fun songs(): List<JSONObject> = TvUiFixture.songs().take(3).mapIndexed { index, source ->
            source.put("title", listOf("测试纯音频", "测试双音轨视频", "测试无歌词音频")[index])
                .put("artists", JSONArray().put("合成测试信号"))
                .put("durationMs", DURATION_MS)
                .also { song -> if (index < 2) {
                    song.put("album", "仅限播放器自动化验收").put("releaseDate", "2026-11-05")
                        .put("classification", JSONObject().put("songId", song.getString("id")).put("revision", 1)
                            .put("dimensions", JSONObject().put("GENRE", JSONObject().put("tagIds", JSONArray().put("test-synthetic-genre"))
                                .put("origin", "MANUAL").put("locked", true).put("confirmed", true))))
                } }
        }

        fun classificationTags(): List<JSONObject> = listOf(JSONObject().put("id", "test-synthetic-genre").put("dimension", "GENRE")
            .put("name", "合成测试").put("active", true).put("aliases", JSONArray()).put("revision", 1).put("count", 2))

        /** Quiet independent sine waves on L/R; generated only in test memory, with no microphone/media access. */
        private fun wave(): ByteArray {
            val rate = 24000
            val samples = (DURATION_MS * rate / 1000).toInt()
            val dataSize = samples * 4
            return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(2)
                putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
                repeat(samples) { index ->
                    putShort((sin(2 * PI * 440 * index / rate) * 600).toInt().toShort())
                    putShort((sin(2 * PI * 660 * index / rate) * 600).toInt().toShort())
                }
            }.array()
        }
    }
}
