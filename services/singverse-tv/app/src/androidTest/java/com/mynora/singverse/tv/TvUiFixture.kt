package com.mynora.singverse.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Small test-only HTTP fixture; never reads NAS credentials, media or application databases. */
internal class TvUiFixture(private val playback: TvPlaybackFixture? = null) : AutoCloseable {
    private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${socket.localPort}"
    val token = "ui-fixture-${UUID.randomUUID()}"
    val invitationUrl get() = "$base/party/#invite=$token"
    val actions = CopyOnWriteArrayList<String>()
    val commandRequests = CopyOnWriteArrayList<JSONObject>()
    val httpEvents = CopyOnWriteArrayList<String>()
    val songRequests = CopyOnWriteArrayList<String>()
    @Volatile var extraGenreTags = 0
    @Volatile var songCount = 20
    @Volatile var mixedSongMetadata = false
    @Volatile var addDelayMs = 0L
    @Volatile var sessionId = "ui-test-session"
    val rejectNextAdd = AtomicInteger()
    val failNextAddResponse = AtomicInteger()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connectionNumber = AtomicInteger()
    private val connections = Executors.newFixedThreadPool(8) { task ->
        Thread(task, "tv-ui-http-${connectionNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val queue = JSONArray()
    private var revision = 1L
    private val addReceipts = mutableSetOf<String>()
    private val covers = (1..20).associateWith(::coverPng)
    private val worker = thread(name = "tv-ui-fixture", isDaemon = true) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: SocketException) { break }
            clients.add(client)
            try { connections.execute { serve(client) } }
            catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
        }
    }

    private fun serve(client: Socket) {
            val started = System.nanoTime()
            var description = "incomplete request"
            try { client.use {
                it.soTimeout = 3000
                val input = it.getInputStream().buffered()
                fun line(): String = buildString {
                    while (true) {
                        val byte = input.read()
                        if (byte < 0 || byte == 10) break
                        if (byte != 13) append(byte.toChar())
                    }
                }
                val request = line().split(' ')
                var length = 0
                var range = ""
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    if (header.startsWith("Range:", true)) range = header.substringAfter(':').trim()
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                val path = request.getOrElse(1) { "/" }.substringBefore('?')
                description = "${request.firstOrNull()} $path"
                httpEvents.add("begin $description")
                val coverNumber = Regex("/api/v1/tv/library/songs/test-song-(\\d+)/cover").matchEntire(path)?.groupValues?.get(1)?.toInt()
                val media = playback?.media(path)
                val jsonBody = if (length > 0) JSONObject(String(body, Charsets.UTF_8)) else JSONObject()
                val adding = path.endsWith("/commands") && jsonBody.optString("action") == "ADD"
                if (path.endsWith("/commands")) commandRequests.add(JSONObject(jsonBody.toString()))
                if (adding && addDelayMs > 0) Thread.sleep(addDelayMs)
                var responseStatus = 200
                val bytes = if (media != null) media.first else if (coverNumber != null) covers.getValue(coverNumber)
                    else try {
                        val rejection = if (adding) rejectNextAdd.getAndSet(0) else 0
                        if (rejection > 0) throw FixtureHttpException(rejection, "测试：本场已暂停接单")
                        response(path, jsonBody, request.getOrElse(1) { path }).also {
                            playback?.beforeSend(path, jsonBody, it)
                            val failure = if (adding) failNextAddResponse.getAndSet(0) else 0
                            if (failure > 0) throw FixtureHttpException(failure, "测试：命令回报中断，请核对后重试")
                        }.toByteArray(Charsets.UTF_8)
                    } catch (error: FixtureHttpException) {
                        responseStatus = error.status
                        JSONObject().put("detail", error.message).toString().toByteArray(Charsets.UTF_8)
                    }
                val contentType = media?.second ?: if (coverNumber != null) "image/png" else "application/json; charset=utf-8"
                val requested = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
                val start = requested?.groupValues?.get(1)?.toLongOrNull() ?: 0
                val end = requested?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(bytes.lastIndex.toLong()) ?: bytes.lastIndex.toLong()
                val invalidRange = requested != null && (start > end || start >= bytes.size)
                val count = if (invalidRange) 0 else (end - start + 1).toInt()
                val status = if (responseStatus != 200) "$responseStatus Fixture Failure" else if (invalidRange) "416 Range Not Satisfiable" else if (requested != null) "206 Partial Content" else "200 OK"
                val rangeHeader = when {
                    invalidRange -> "Content-Range: bytes */${bytes.size}\r\n"
                    requested != null -> "Content-Range: bytes $start-$end/${bytes.size}\r\n"
                    else -> ""
                }
                it.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nAccept-Ranges: bytes\r\n" + rangeHeader +
                    "Content-Length: $count\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                if (!invalidRange && request.firstOrNull() != "HEAD") it.getOutputStream().write(bytes, start.toInt(), count)
                httpEvents.add("end $description status=$status bytes=$count elapsedMs=${(System.nanoTime() - started) / 1000000}")
            } } catch (_: java.io.IOException) {
                // Media3 may cancel a buffered HTTP read when seeking or replacing the source.
                httpEvents.add("cancel $description elapsedMs=${(System.nanoTime() - started) / 1000000}")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                httpEvents.add("error $description ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                clients.remove(client)
            }
    }

    private class FixtureHttpException(val status: Int, message: String) : RuntimeException(message)

    @Synchronized private fun response(path: String, body: JSONObject, requestTarget: String): String {
        if (path.endsWith("/commands")) actions.add(body.getString("action"))
        playback?.response(path, body)?.let { return it }
        return when {
        path.endsWith("/invitations") -> JSONObject().put("invitation", token)
            .put("expiresAt", "2099-01-01T00:00:00Z").toString()
        path.endsWith("/participants") -> JSONArray().put(JSONObject().put("id", "test-friend").put("name", "测试亲友").put("revoked", false))
            .put(JSONObject().put("id", "test-revoked").put("name", "已撤销测试设备").put("revoked", true)).toString()
        path.endsWith("/commands") -> {
            if (path.substringAfter("/sessions/").substringBefore('/') != sessionId) throw FixtureHttpException(410, "旧聚会已结束")
            val action = body.getString("action")
            if (action == "ADD" && body.optString("requestId") !in addReceipts) {
                if (body.optLong("expectedRevision") != revision) throw FixtureHttpException(409, "队列已更新，请核对后重试")
                val song = songs(songCount, mixedSongMetadata).first { it.optString("id") == body.optString("songId") }
                queue.put(JSONObject().put("id", "test-queue-${queue.length()}").put("songId", song.getString("id"))
                    .put("song", song).put("status", "WAITING").put("ownerName", "测试电视"))
                addReceipts.add(body.getString("requestId"))
                revision++
            }
            currentSnapshot().toString()
        }
        path.endsWith("/snapshot") || path.endsWith("/recover") || path == "/api/v1/tv/sessions" -> currentSnapshot().toString()
        path == "/api/v1/tv/home" -> JSONObject().put("recentSongs", JSONArray(songs())).put("totalSongs", 20)
            .put("server", JSONObject().put("name", "UI 验收测试服务")).put("appearance", appearance()).put("featuredPlaylists", JSONArray(playlists())).toString()
        path.matches(Regex("/api/v1/tv/library/songs/test-song-\\d+")) -> songs(songCount, mixedSongMetadata).first { it.optString("id") == path.substringAfterLast('/') }.toString()
        path == "/api/v1/tv/library/songs" || path == "/api/v1/tv/library/artists/test-artist/songs" || path == "/api/v1/tv/library/playlists/test-playlist/songs" -> {
            songRequests.add(requestTarget)
            val parameters = android.net.Uri.parse(requestTarget)
            val page = parameters.getQueryParameter("page")?.toIntOrNull() ?: 0
            val rows = songs(songCount, mixedSongMetadata)
            JSONObject().put("items", JSONArray(rows.drop(page * 20).take(20))).put("totalElements", rows.size).toString()
        }
        path == "/api/v1/tv/library/classification-tags" -> JSONArray()
            .put(JSONObject().put("id", "tag-language").put("dimension", "LANGUAGE").put("name", "国语").put("count", 20))
            .put(JSONObject().put("id", "tag-genre").put("dimension", "GENRE").put("name", "流行").put("count", 12))
            .apply { repeat(extraGenreTags) { index -> put(JSONObject().put("id", "tag-genre-$index").put("dimension", "GENRE").put("name", "风格 ${index + 1}").put("count", 20)) } }.toString()
        path == "/api/v1/tv/library/artists" -> JSONObject().put("items", JSONArray(artists())).put("totalElements", 1).toString()
        path == "/api/v1/tv/library/artists/test-artist" -> artists().first().toString()
        path == "/api/v1/tv/library/playlists" -> JSONObject().put("items", JSONArray(playlists())).put("totalElements", 1).toString()
        path == "/api/v1/tv/library/playlists/test-playlist" -> playlists().first().toString()
        else -> JSONObject().toString()
        }
    }

    private fun currentSnapshot(): JSONObject = snapshot().put("id", sessionId).put("queue", JSONArray(queue.toString())).put("revision", revision).put("stateRevision", revision)

    override fun close() {
        socket.close()
        playback?.releaseNextResponse()
        clients.forEach { runCatching { it.close() } }
        connections.shutdownNow()
        worker.join(1000)
        connections.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        fun snapshot(): JSONObject = JSONObject().put("id", "ui-test-session").put("status", "ACTIVE")
            .put("revision", 1).put("stateRevision", 1).put("acceptingRequests", true)
            .put("queue", JSONArray()).put("commands", JSONArray())

        fun songs(count: Int = 20, mixedMetadata: Boolean = false): List<JSONObject> = (1..count).map { number ->
            JSONObject().put("id", "test-song-$number").put("title", "测试歌曲 ${number.toString().padStart(2, '0')}")
                .put("artists", JSONArray().put(when (number) { 1 -> "B.A.D."; 2 -> "gypq 测试歌手"; else -> "测试歌手 $number" })).put("revision", "test-v1")
                .put("coverPath", "/api/v1/tv/library/songs/test-song-${(number - 1) % 20 + 1}/cover")
                .put("sourceName", "仅限 UI 自动化验收").put("durationMs", 180000)
                .put("version", "ORIGINAL").also { song ->
                    if (mixedMetadata && number == 1) {
                        song.put("title", "长歌名测试：My Lovely Girl 与今晚一起唱的歌，检查完整表格行和详情")
                            .put("artists", JSONArray().put("B.A.D. / gypq 中文歌手组合，长署名测试"))
                    }
                    if (mixedMetadata && number == 2) {
                        song.remove("coverPath"); song.remove("durationMs"); song.remove("version")
                    }
                }
        }

        private fun artists(): List<JSONObject> = listOf(
            JSONObject().put("id", "test-artist").put("name", "测试歌手").put("count", 20)
                .put("managed", false).put("biography", "仅用于隔离 UI 验收"),
        )

        private fun playlists(): List<JSONObject> = listOf(
            JSONObject().put("id", "test-playlist").put("name", "今晚一起唱").put("count", 20)
                .put("description", "仅用于隔离 UI 验收").put("coverVersion", "test-v1")
                .put("coverPath", "/api/v1/tv/library/songs/test-song-1/cover"),
        )

        /** Include landscape and portrait originals to exercise production ContentScale.Crop. */
        private fun coverPng(number: Int): ByteArray {
            val width = if (number == 1) 320 else 180
            val height = if (number == 2) 280 else 180
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.HSVToColor(floatArrayOf((number * 29f) % 360f, .52f, .54f)))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(85, 255, 255, 255)
            canvas.drawCircle(width * .5f, height * .45f, minOf(width, height) * .3f, paint)
            paint.color = Color.argb(180, 15, 20, 35)
            canvas.drawCircle(width * .5f, height * .45f, minOf(width, height) * .13f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 22f
            canvas.drawText("UI TEST ${number.toString().padStart(2, '0')}", width * .5f, height * .82f, paint)
            return ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }

        fun appearance(): JSONObject = JSONObject().put("headline", "遥控器验收 · 测试曲库")
            .put("subtitle", "仅测试数据，不访问真实 NAS").put("autoRotate", false).put("banners", JSONArray())
    }
}
