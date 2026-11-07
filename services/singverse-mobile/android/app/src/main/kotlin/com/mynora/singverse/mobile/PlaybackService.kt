@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.mynora.singverse.mobile

import android.content.Intent
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.datasource.DefaultDataSource
import android.media.*
import android.os.*
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow
import java.util.UUID

class PlaybackService : MediaSessionService() {
    lateinit var player: ExoPlayer; private set
    private lateinit var session: MediaSession
    lateinit var karaoke: KaraokeEngine; private set
    private lateinit var chain: KaraokeAudioChain
    @Volatile private var destroyed = false
    private var countdown = 0
    private var countdownGeneration = 0
    private var pitch = 0
    private var speed = 1f
    private var rangeA = 0L
    private var rangeB = 0L
    private var workId = ""
    private val roles by lazy { getSharedPreferences("singing-roles", MODE_PRIVATE) }
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { routeChanged() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { routeChanged() }
    }
    private fun routeChanged() { if (::karaoke.isInitialized && karaoke.phase == "RECORDING") finishRecording("音频设备已改变，录音片段已保留") }
    private lateinit var transport: NasTransport
    private val main = Handler(Looper.getMainLooper())
    private val requests = PlaybackRequests()
    private val historyReporter = PlaybackHistoryReporter()
    private val historyClock = ActualPlaybackClock(SystemClock::elapsedRealtime)
    private var historyAttempt: PlaybackHistoryReporter.Attempt? = null
    private var historyConnection: NasConnection? = null
    private var historySongId = ""
    private var historyTransitioning = false
    private var historyLastState = ""
    private var historyLastReportAt = 0L
    private var requestScope = NasRequestScope()
    @Volatile private var generation = 0
    private var leaseId = ""
    private var offsetMs = 0L
    private var sharedRoles = JSONObject()
    private var selectedSourceId = ""
    private var sourceAudioIds = listOf<String>()
    private var requestedAudioRole = ""
    private var backingUrl = ""
    private var originalDuration = 0L
    private var resourceMode = ""
    private var song = JSONObject()
    private var server = ""
    private var resourceReady = false
    private var requestInFlight = false
    private var force = false
    private var playbackOrigin = ""
    private var playAfterPreparation = true
    private var requestedPosition = 0L
    private var message = ""
    private var state = "IDLE"
    private var nextLeaseAt = 0L
    private var lastLeaseSuccessAt = 0L
    private var preparationStartedAt = 0L
    private var focusRequest: AudioFocusRequest? = null
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val focus = AudioManager.OnAudioFocusChangeListener { change ->
        // A regained focus never silently starts sound again.
        if (change < 0) main.post { finishRecording("音频焦点已中断，录音片段已保留"); playAfterPreparation = false; player.pause(); publish() }
    }

    override fun onCreate() {
        super.onCreate()
        transport = NasTransport.get(this)
        karaoke = KaraokeEngine(this, { chain.audioTrack }, { publish() }, { finishRecording(it) })
        chain = KaraokeAudioChain { karaoke.appendBacking(it) }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, floatOutput: Boolean, playbackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessorChain(chain)
                    .setAudioTrackProvider { config, attributes, id -> DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, id).also { chain.audioTrack = it } }.build()
        }
        renderers.setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(transport.mediaClient())))).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder().setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
        audio.registerAudioDeviceCallback(routeCallback, main)
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Only our application and Android's trusted media controllers can control playback.
                return if (controller.packageName == packageName || controller.isTrusted)
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(Player.Commands.Builder().addAll(
                            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_VOLUME,
                            Player.COMMAND_GET_AUDIO_ATTRIBUTES).build()).build()
                    else MediaSession.ConnectionResult.reject()
            }
            override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, command: Int): Int {
                if (command == Player.COMMAND_PLAY_PAUSE && !player.isPlaying && !requestFocus()) return androidx.media3.session.SessionError.ERROR_INVALID_STATE
                return SessionResult.RESULT_SUCCESS
            }
        }).build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (resourceMode != "DIRECT" || selectedSourceId.isBlank()) return
                val available = availableAudioTracks(tracks)
                if (available.size != sourceAudioIds.size) return
                val ordinal = sourceAudioIds.indexOf(selectedSourceId)
                val target = available.getOrNull(ordinal) ?: return
                val group = tracks.groups[target.groupIndex]
                if (!target.selected) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, target.trackIndex)).build()
                selectedSourceId = ""
            }
            override fun onEvents(p: Player, events: Player.Events) {
                if (resourceReady && state !in listOf("ERROR", "AUTH_REQUIRED")) state = when (p.playbackState) {
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_ENDED -> "ENDED"
                    Player.STATE_READY -> if (p.isPlaying) "PLAYING" else "PAUSED"
                    else -> state
                }
                if (karaoke.phase == "RECORDING" && p.playbackState == Player.STATE_ENDED) finishRecording("", true)
                if (karaoke.phase == "RECORDING" && p.playbackState == Player.STATE_READY && !p.playWhenReady) finishRecording("演唱已暂停，录音片段已保留")
                publish()
            }
            override fun onPlayerError(error: PlaybackException) {
                requestedPosition = player.currentPosition + offsetMs
                var cause: Throwable? = error
                var expired = false
                while (cause != null) {
                    if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode in listOf(401, 403)) expired = true
                    cause = cause.cause
                }
                if (!expired && song.optString("id").isNotBlank() && playbackOrigin == transport.origin &&
                    PlaybackRecovery.compatible(error.errorCode, force, workId.isNotEmpty(), karaoke.phase in listOf("RECORDING", "FINALIZING") || countdown > 0)) {
                    open(song, server, requestedPosition, compatible = true, autoplay = player.playWhenReady, preserveSettings = true)
                    message = "当前格式无法在手机上解码，正在准备兼容播放"; publish(); return
                }
                state = if (expired) "AUTH_REQUIRED" else "ERROR"
                message = if (expired) "登录已失效，请重新登录后继续" else "播放失败，可重试或使用兼容播放"
                resourceReady = false
                finishRecording(message)
                requestScope.cancel(); cancelResource(leaseId); leaseId = ""
                publish()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && workId.isEmpty()) {
                    finishHistory("COMPLETED")
                    beginHistory()
                }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && resourceMode == "HLS" && offsetMs > 0)
                    open(song, server, 0, force, preserveSettings = true)
            }
        })
        current = this
        main.post(tick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady && karaoke.phase != "RECORDING" && countdown == 0) stopSelf()
    }
    override fun onDestroy() {
        finishHistory("STOPPED")
        historyReporter.close()
        destroyed = true
        requestScope.cancel()
        karaoke.close()
        audio.unregisterAudioDeviceCallback(routeCallback)
        generation++
        cancelResource(leaseId, playbackOrigin)
        main.removeCallbacksAndMessages(null)
        session.release(); player.release(); abandonFocus()
        requests.close()
        if (current === this) current = null
        super.onDestroy()
    }

    fun open(value: JSONObject, instanceId: String, position: Long = 0, compatible: Boolean = false, autoplay: Boolean = true, preserveSettings: Boolean = false, reuseAttempt: Boolean = false) {
        require(karaoke.phase !in listOf("RECORDING", "FINALIZING") && countdown == 0) { "请先结束并保存当前录音" }
        karaoke.testMicrophone(false)
        val keepHistory = (preserveSettings || reuseAttempt) && historyAttempt?.invalid == false && historyConnection == transport.connectionIdentity() &&
            historySongId == value.optString("id") && server == instanceId && workId.isEmpty()
        if (!keepHistory) finishHistory("STOPPED")
        historyTransitioning = true
        historyClock.sample(false)
        workId = ""
        backingUrl = ""; selectedSourceId = ""
        if (!preserveSettings) {
            requestedAudioRole = ""; backingUrl = ""; sharedRoles = JSONObject()
            pitch = 0; speed = 1f; rangeA = 0; rangeB = 0
            player.playbackParameters = PlaybackParameters.DEFAULT
            chain.channels.mode = "STEREO"
        }
        val previous = leaseId
        val previousOrigin = playbackOrigin
        requestScope.cancel(); requestScope = NasRequestScope()
        val scope = requestScope
        generation++
        val ticket = generation
        player.stop(); player.clearMediaItems()
        song = value; server = instanceId; force = compatible; requestedPosition = max(0, position)
        playbackOrigin = transport.origin; playAfterPreparation = autoplay
        val expectedOrigin = playbackOrigin
        val requestedRole = requestedAudioRole
        originalDuration = value.optLong("durationMs", 0); offsetMs = 0; resourceMode = ""
        state = "PREPARING"; message = "正在准备播放"; leaseId = ""; resourceReady = false; requestInFlight = true
        if (!keepHistory) beginHistory()
        historyTransitioning = false
        preparationStartedAt = SystemClock.elapsedRealtime()
        publish()
        cancelResource(previous, previousOrigin)
        requests.open(scope) opening@{
            try {
                if (!currentRequest(ticket, expectedOrigin, scope)) return@opening
                val options = JSONObject().put("client", capabilities()).put("start", max(0, position) / 1000.0).put("force", compatible).put("audioRole", requestedRole)
                val result = JSONObject(transport.scopedRequest(expectedOrigin, "/api/v1/transcoding/playback/${value.getString("id")}", "POST", options.toString(), scope))
                main.post {
                    if (currentRequest(ticket, expectedOrigin, scope)) {
                        requestInFlight = false
                        try { applyResource(result, ticket) } catch (error: Exception) { fail(error) }
                    }
                    else cancelResource(result.optString("id"), expectedOrigin)
                }
            } catch (error: Exception) { main.post { if (currentRequest(ticket, expectedOrigin, scope)) fail(error) } }
        }
    }

    private fun currentRequest(ticket: Int, origin: String, scope: NasRequestScope) =
        !destroyed && ticket == generation && origin == transport.origin && !scope.cancelled

    private fun cancelResource(id: String, expectedOrigin: String = playbackOrigin) {
        if (id.isNotBlank()) requests.cancel {
            if (transport.origin == expectedOrigin) runCatching {
                transport.scopedRequest(expectedOrigin, "/api/v1/transcoding/sessions/$id/cancel", "POST", scope = NasRequestScope(5_000))
            }
        }
    }

    private fun applyResource(result: JSONObject, ticket: Int) {
        if (destroyed || ticket != generation || playbackOrigin != transport.origin) return
        if (result.has("audioRoles")) sharedRoles = result.getJSONObject("audioRoles")
        if (result.has("backingUrl")) backingUrl = result.getString("backingUrl")
        resourceMode = result.optString("mode", resourceMode)
        leaseId = result.optString("id", leaseId)
        lastLeaseSuccessAt = SystemClock.elapsedRealtime()
        if (result.optString("state") in listOf("FAILED", "CANCELLED") || result.optString("status") in listOf("FAILED", "CANCELLED")) {
            fail(Exception(result.optString("error", "兼容播放准备失败"))); return
        }
        if (result.optString("status") != "READY") {
            state = "PREPARING"; message = "正在准备兼容播放…"
            nextLeaseAt = SystemClock.elapsedRealtime() + PlaybackRecovery.PREPARATION_POLL_MS; publish(); return
        }
        val media = result.optJSONObject("media") ?: result.optJSONObject("execution")?.optJSONObject("media")
        if (media != null && media.optDouble("duration", 0.0) > 0) originalDuration = (media.optDouble("duration") * 1000).toLong()
        offsetMs = (result.optDouble("offset", 0.0) * 1000).toLong()
        val mapped = result.optJSONObject("execution")?.optJSONArray("trackMapping")
        val streams = mapped ?: media?.optJSONArray("streams") ?: sharedRoles.optJSONArray("streams")
        sourceAudioIds = (0 until (streams?.length() ?: 0)).map { streams!!.getJSONObject(it) }.filter { it.optString("kind") == "audio" }.map { it.optString(if (mapped != null) "sourceId" else "id") }
        selectedSourceId = if (resourceMode == "DIRECT" && backingUrl.isBlank()) sharedRoles.optString(if (requestedAudioRole == "original") "originalId" else if (requestedAudioRole == "backing") "backingId" else "") else ""
        val builder = MediaItem.Builder().setMediaId(song.getString("id")).setUri(transport.resolve(result.getString("url")))
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(song.optString("title")).setArtist(song.optJSONArray("artists")?.let { (0 until it.length()).joinToString(" / ") { i -> it.getString(i) } }).build())
        if (resourceMode == "HLS") builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val subtitle = result.optString("subtitleUrl")
        if (subtitle.isNotBlank()) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(transport.resolve(subtitle)))
            .setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        if (ticket != generation) return
        if (requestedAudioRole == "backing" && backingUrl.isNotBlank()) {
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(transport.mediaClient())))
            val original = androidx.media3.exoplayer.source.FilteringMediaSource(factory.createMediaSource(builder.build()), setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT))
            val backing = factory.createMediaSource(MediaItem.Builder().setUri(transport.resolve(backingUrl)).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(offsetMs).build()).build())
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(true, original, backing), max(0, requestedPosition - offsetMs))
        } else player.setMediaItem(builder.build(), max(0, requestedPosition - offsetMs))
        resourceReady = true; message = ""; state = "BUFFERING"; nextLeaseAt = SystemClock.elapsedRealtime() + PlaybackRecovery.LEASE_POLL_MS
        player.prepare()
        if (playAfterPreparation && requestFocus()) player.play() else { state = "PAUSED"; message = if (playAfterPreparation) "暂时无法取得音频焦点，请稍后继续" else "" }
        publish()
    }

    private fun fail(error: Exception) {
        if (resourceReady) requestedPosition = player.currentPosition + offsetMs
        requestInFlight = false; resourceReady = false; finishRecording(error.message ?: "歌曲连接中断"); player.pause()
        state = if (error is NasFailure && error.status in listOf(401, 403)) "AUTH_REQUIRED" else "ERROR"
        message = if (state == "AUTH_REQUIRED") "登录已失效，请重新登录后继续" else error.message ?: "播放失败"
        requestScope.cancel(); cancelResource(leaseId); leaseId = ""
        publish()
    }

    fun command(action: String, value: Any?) {
        require(action !in listOf("seek", "restart", "track", "pitch", "speed", "channel", "range", "role", "useRole", "stop") || karaoke.phase !in listOf("RECORDING", "FINALIZING") && countdown == 0) { "请先结束当前录音，再修改演唱设置" }
        when (action) {
            "pitch" -> { pitch = (value as Number).toInt().coerceIn(-6, 6); player.playbackParameters = PlaybackParameters(speed, 2.0.pow(pitch / 12.0).toFloat()) }
            "speed" -> { speed = (value as Number).toFloat().coerceIn(.6f, 1.2f); player.playbackParameters = PlaybackParameters(speed, 2.0.pow(pitch / 12.0).toFloat()) }
            "volume" -> player.volume = (value as Number).toFloat().coerceIn(0f, 1f)
            "channel" -> { require(value == "STEREO" || player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && (0 until it.length).any { i -> it.isTrackSelected(i) && it.getTrackFormat(i).channelCount == 2 } }) { "当前音轨不支持左右声道选择" }; chain.channels.mode = value.toString() }
            "range" -> { val values = value as Map<*, *>; val a = (values["a"] as Number).toLong(); val b = (values["b"] as Number).toLong(); require(a >= 0 && (b == 0L || b > a && b <= duration())) { "请选择有效的 A–B 区间" }; rangeA = a; rangeB = b }
            "role" -> {
                val tracks = (snapshot()["tracks"] as List<*>).map { it as Map<*, *> }.filter { it["type"] == "audio" }
                val selected = tracks.firstOrNull { it["selected"] == true } ?: error("尚未识别音轨")
                val source = selected["sourceId"]?.toString().orEmpty()
                if (tracks.size == 1 && chain.channels.mode in listOf("LEFT", "RIGHT") && selected["channels"] == 2) {
                    roles.edit().putString(roleKey(value.toString()), selected["id"].toString() + "|" + chain.channels.mode).apply()
                    message = "左右声道标记已保存到本机，仅此素材版本生效"; publish(); return
                }
                val other = tracks.firstOrNull { it["sourceId"] != source }?.get("sourceId")?.toString().orEmpty()
                require(source.isNotBlank() && other.isNotBlank()) { "请先完成源音轨探测" }
                val original = if (value.toString() == "original") source else other
                val backing = if (value.toString() == "backing") source else other
                val path = "/api/v1/media-songs/${song.getString("id")}/audio-roles"
                val roleOrigin = playbackOrigin
                val roleSong = song.optString("id")
                val scope = requestScope; val ticket = generation
                requests.metadata(scope) { try {
                    val current = JSONObject(transport.scopedRequest(roleOrigin, path, "GET", scope = scope))
                    val saved = JSONObject(transport.scopedRequest(roleOrigin, path, "PUT", JSONObject().put("originalId", original).put("backingId", backing).put("revision", current.getLong("revision")).toString(), scope))
                    main.post { if (currentRequest(ticket, roleOrigin, scope) && song.optString("id") == roleSong) { sharedRoles = saved; publish() } }
                } catch(e: Exception) { main.post { if (currentRequest(ticket, roleOrigin, scope)) { message = e.message ?: "保存失败"; publish() } } } }
            }
            "useRole" -> {
                val saved = roles.getString(roleKey(value.toString()), "").orEmpty().split('|')
                val localTrack = availableAudioTracks().singleOrNull()
                if (saved.size == 2 && saved[1] in listOf("LEFT", "RIGHT") && localTrack != null && localTrack.id == saved[0] && localTrack.channelCount == 2) {
                    command("track", saved[0]); command("channel", saved[1]); return
                }
                chain.channels.mode = "STEREO"
                requestedAudioRole = value.toString()
                open(song, server, player.currentPosition + offsetMs, force, player.playWhenReady, preserveSettings = true)
            }
            "record" -> { require(value == true || usingBacking() || song.optString("version") in listOf("ACCOMPANIMENT", "INSTRUMENTAL")) { "请先确认伴奏，或明确选择跟原唱录唱" }; beginRecording() }
            "endRecord" -> finishRecording("主动结束录唱", false)
            "cancelCountdown" -> { countdownGeneration++; countdown = 0; stopRecordingForeground() }

            "play" -> { playAfterPreparation = true; if (resourceReady && state !in listOf("ERROR", "AUTH_REQUIRED") && requestFocus()) { message = ""; player.play() } }
            "pause" -> { finishRecording("演唱已暂停，录音片段已保留"); playAfterPreparation = false; player.pause() }
            "restart" -> { finishHistory("STOPPED"); beginHistory(); command("seek", value ?: 0L) }
            "stop" -> {
                finishHistory("STOPPED")
                requestScope.cancel()
                generation++; player.stop(); player.clearMediaItems(); cancelResource(leaseId); leaseId = ""
                resourceReady = false; song = JSONObject(); server = ""; state = "IDLE"; message = ""; abandonFocus()
            }
            "seek" -> {
                val position = (value as Number).toLong().coerceAtLeast(0)
                if (resourceMode == "HLS") {
                    val local = PlaybackRecovery.localHlsSeek(position, offsetMs, player.duration, resourceReady && player.isCurrentMediaItemSeekable)
                    if (local != null) player.seekTo(local)
                    else open(song, server, position, force, player.playWhenReady, preserveSettings = true)
                } else player.seekTo(position)
            }
            "loop" -> player.repeatMode = if (value == true) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            "track" -> {
                val parts = value.toString().split(':')
                val group = player.currentTracks.groups.getOrNull(parts[0].toIntOrNull() ?: -1)
                val index = parts.getOrNull(1)?.toIntOrNull() ?: -1
                if (group != null && index in 0 until group.length && group.isTrackSupported(index) &&
                    (group.type != C.TRACK_TYPE_AUDIO || availableAudioTracks().any { it.id == value.toString() }))
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(group.type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
            }
            "subtitles" -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, value != true).build()
        }
        publish()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state == "PREPARING" && PlaybackRecovery.preparationExpired(preparationStartedAt, SystemClock.elapsedRealtime())) {
                fail(java.io.IOException("播放准备超时，请重试或检查 NAS 计算节点"))
            }
            if (workId.isEmpty() && playbackOrigin.isNotBlank() && playbackOrigin != transport.origin && state !in listOf("ERROR", "AUTH_REQUIRED", "IDLE")) {
                historyReporter.invalidate(historyAttempt); historyAttempt = null; historyClock.clear()
                requestScope.cancel(); generation++; leaseId = ""; resourceReady = false
                finishRecording("NAS 已切换，录音片段已保留"); player.stop(); player.clearMediaItems()
                state = "ERROR"; message = "NAS 已切换，请重新选歌"; publish()
            }
            if (leaseId.isNotBlank() && state !in listOf("ERROR", "AUTH_REQUIRED", "ENDED", "IDLE") && !requestInFlight && SystemClock.elapsedRealtime() >= nextLeaseAt) {
                requestInFlight = true
                val id = leaseId; val ticket = generation; val expectedOrigin = playbackOrigin
                val scope = requestScope
                requests.poll(scope) {
                    try {
                        if (!currentRequest(ticket, expectedOrigin, scope)) return@poll
                        val result = JSONObject(transport.scopedRequest(expectedOrigin, "/api/v1/transcoding/sessions/$id", scope = scope))
                        main.post { if (currentRequest(ticket, expectedOrigin, scope)) {
                            requestInFlight = false
                            lastLeaseSuccessAt = SystemClock.elapsedRealtime()
                            nextLeaseAt = lastLeaseSuccessAt + PlaybackRecovery.LEASE_POLL_MS
                            if (result.optString("state") in listOf("FAILED", "CANCELLED")) fail(Exception(result.optString("error", "播放资源已失效")))
                            else if (!resourceReady) { try { applyResource(result, ticket) } catch (error: Exception) { fail(error) } }
                        } }
                    } catch (e: Exception) { main.post { if (currentRequest(ticket, expectedOrigin, scope)) {
                        requestInFlight = false
                        val transient = e is java.io.IOException || e is NasFailure && (e.status == 429 || e.status >= 500)
                        if (transient && SystemClock.elapsedRealtime() - lastLeaseSuccessAt < PlaybackRecovery.LEASE_FAILURE_LIMIT_MS) {
                            nextLeaseAt = SystemClock.elapsedRealtime() + PlaybackRecovery.LEASE_RETRY_MS
                        } else fail(e)
                    } } }
                }
            }
            if (rangeB > rangeA && player.isPlaying && player.currentPosition + offsetMs >= rangeB) {
                if (karaoke.phase == "RECORDING") finishRecording("", true)
                else if (countdown == 0) command("seek", rangeA)
            }
            reportHistory()
            if (player.isPlaying || karaoke.phase in listOf("RECORDING", "TESTING") || countdown > 0) publish()
            main.postDelayed(this, 100)
        }
    }

    private fun availableAudioTracks(tracks: Tracks = player.currentTracks): List<PlaybackAudioTrack> {
        val available = tracks.groups.flatMapIndexed { g, group ->
            (0 until group.length).filter { group.type == C.TRACK_TYPE_AUDIO && group.isTrackSupported(it) }.map { i ->
                val format = group.getTrackFormat(i)
                PlaybackAudioTrack(g, i, group.isTrackSelected(i), format.sampleMimeType, format.channelCount, format.sampleRate)
            }
        }
        val manifest = if (resourceMode == "HLS") player.currentManifest as? HlsManifest else null
        val variant = manifest?.multivariantPlaylist?.variants?.singleOrNull()
        val rendition = manifest?.multivariantPlaylist?.audios?.singleOrNull()
        val duplicate = variant != null && rendition?.url != null && variant.url == rendition.url && variant.audioGroupId == rendition.groupId
        val decoded = player.audioFormat?.let { PlaybackAudioFormat(it.sampleMimeType, it.channelCount, it.sampleRate) }
        return PlaybackAudioTracks.usable(available, sourceAudioIds.count { it.isNotBlank() }, duplicate, decoded)
    }

    fun snapshot(): Map<String, Any?> {
        if (resourceReady && state !in listOf("ERROR", "AUTH_REQUIRED")) state = when (player.playbackState) {
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_ENDED -> "ENDED"
            Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"
            else -> state
        }
        var audioOrdinal = 0
        val availableAudio = availableAudioTracks()
        val audioIds = availableAudio.map { it.id }.toSet()
        val audioCount = availableAudio.size
        val tracks = player.currentTracks.groups.flatMapIndexed { g, group -> (0 until group.length).filter { group.isTrackSupported(it) && (group.type == C.TRACK_TYPE_TEXT || group.type == C.TRACK_TYPE_AUDIO && "$g:$it" in audioIds) }.map { i ->
            val f = group.getTrackFormat(i)
            mapOf("sourceId" to if(group.type == C.TRACK_TYPE_AUDIO && audioCount == sourceAudioIds.size) sourceAudioIds.getOrElse(audioOrdinal++) { "" } else "", "id" to "$g:$i", "type" to if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "text", "selected" to group.isTrackSelected(i), "channels" to f.channelCount,
                "label" to listOfNotNull(f.label, f.language?.takeUnless { it == "und" }, f.channelCount.takeIf { it > 0 }?.let { "$it 声道" }).joinToString(" · ").ifBlank { "轨道 ${i + 1}" })
        } }
        val selectedAudio = tracks.firstOrNull { it["type"] == "audio" && it["selected"] == true }
        val localBacking = roles.getString(roleKey("backing"), "") == selectedAudio?.get("id").toString() + "|" + chain.channels.mode
        val localOriginal = roles.getString(roleKey("original"), "") == selectedAudio?.get("id").toString() + "|" + chain.channels.mode
        val selectedSource = selectedAudio?.get("sourceId").toString()
        val audioRole = when {
            workId.isNotBlank() -> ""
            localBacking || sharedRoles.optString("backingId").isNotBlank() && sharedRoles.optString("backingId") == selectedSource -> "backing"
            localOriginal || sharedRoles.optString("originalId").isNotBlank() && sharedRoles.optString("originalId") == selectedSource -> "original"
            selectedSource.isBlank() || selectedSource == "null" -> requestedAudioRole
            else -> ""
        }
        val videoSize = player.videoSize
        val videoAspect = if (videoSize.width > 0 && videoSize.height > 0) videoSize.width.toDouble() * videoSize.pixelWidthHeightRatio / videoSize.height else 16.0 / 9.0
        val duration = if (originalDuration > 0) originalDuration else player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.plus(offsetMs) ?: 0L
        return mapOf("song" to song.toString(), "instanceId" to server, "state" to state, "message" to message,
            "positionMs" to (if (resourceReady) player.currentPosition + offsetMs else requestedPosition), "durationMs" to duration,
            "isPlaying" to player.isPlaying, "loop" to (player.repeatMode == Player.REPEAT_MODE_ONE), "mode" to resourceMode, "tracks" to tracks, "workId" to workId,
            "karaoke" to karaoke.snapshot(), "countdown" to countdown, "pitch" to pitch, "speed" to speed,
            "volume" to player.volume, "channel" to chain.channels.mode, "rangeA" to rangeA, "rangeB" to rangeB,
            "originalMapped" to (sharedRoles.optString("originalId").isNotBlank() || sourceAudioIds.size == 1), "backingMapped" to (sharedRoles.optString("backingId").isNotBlank() || sharedRoles.has("backingResource") || roles.contains(roleKey("backing"))),
            "audioRole" to audioRole, "videoAspect" to videoAspect,
            "output" to (chain.audioTrack?.routedDevice?.productName?.toString() ?: "播放后显示实际输出设备"))
    }
    private fun publish() { if (!destroyed) { reportHistory(); listener?.invoke(snapshot()) } }
    private fun beginHistory() {
        if (workId.isNotEmpty() || song.optString("id").isBlank()) return
        val connection = transport.connectionIdentity()
        historyConnection = connection; historySongId = song.optString("id")
        historyClock.start(UUID.randomUUID().toString())
        historyAttempt = historyReporter.begin(MobilePlaybackHistoryTransport(transport, connection, historySongId))
        historyLastState = ""; historyLastReportAt = 0
    }
    private fun historyFrame(state: String, sample: ActualPlaybackClock.Sample) = PlaybackHistoryReporter.Frame(state,
        sample.actualPlayedMs, sample.elapsedMs, playbackHistoryDuration(originalDuration, player.duration, offsetMs,
            player.isCurrentMediaItemDynamic, player.isCurrentMediaItemLive), historyClock.startedAtEpochMs,
            historyClock.occurredAtEpochMs, historyClock.lastActivityAtEpochMs)
    private fun reportHistory() {
        val attempt = historyAttempt ?: return
        if (historyConnection != transport.connectionIdentity() || workId.isNotEmpty()) {
            historyReporter.invalidate(attempt); historyAttempt = null; historyClock.clear(); return
        }
        val actual = resourceReady && !historyTransitioning && player.isPlaying && state !in listOf("ERROR", "AUTH_REQUIRED")
        val sample = historyClock.sample(actual)
        val next = when {
            historyTransitioning || state == "PREPARING" -> "PREPARING"
            state == "ENDED" -> "COMPLETED"
            state in listOf("ERROR", "AUTH_REQUIRED") -> "ERROR"
            actual -> "PLAYING"
            state == "BUFFERING" -> "BUFFERING"
            else -> "PAUSED"
        }
        val now = SystemClock.elapsedRealtime()
        if (next != historyLastState || now - historyLastReportAt >= if (actual) 5000 else 15000) {
            historyReporter.offer(attempt, historyFrame(next, sample))
            historyLastState = next; historyLastReportAt = now
        }
    }
    private fun finishHistory(state: String) {
        val attempt = historyAttempt ?: return
        val sample = historyClock.sample(false)
        if (historyConnection == transport.connectionIdentity() && workId.isEmpty()) historyReporter.offer(attempt, historyFrame(state, sample))
        else historyReporter.invalidate(attempt)
        historyAttempt = null; historyClock.clear()
    }
    private fun duration(): Long = (snapshot()["durationMs"] as Number).toLong()
    private fun usingBacking(): Boolean {
        if(requestedAudioRole == "backing" && backingUrl.isNotBlank())return true
        val selected=(snapshot()["tracks"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["type"]=="audio" && it["selected"]==true } ?: return false
        val backingId=sharedRoles.optString("backingId")
        if(backingId.isNotBlank() && selected["sourceId"]==backingId)return true
        return roles.getString(roleKey("backing"),"")==selected["id"].toString()+"|"+chain.channels.mode
    }
    private fun roleKey(role: String) = "$server:${song.optString("id")}:${song.optString("revision")}:$resourceMode:$role"

    fun beginRecording() {
        require(resourceReady && player.playbackState == Player.STATE_READY && workId.isEmpty()) { "请等待歌曲准备完成" }
        require(karaoke.phase !in listOf("RECORDING", "FINALIZING", "RENDERING")) { "请先处理当前录音" }
        karaoke.testMicrophone(false)
        require(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "请先允许麦克风权限" }
        player.pause(); countdown = 3; val token = ++countdownGeneration
        showRecordingForeground()
        fun count() {
            if (token != countdownGeneration || destroyed) return
            if (countdown > 0) { publish(); main.postDelayed({ countdown--; count() }, 1000); return }
            try {
                checkNotNull(player.currentMediaItem) { "歌曲资源已失效" }
                val start = if (rangeB > rangeA) rangeA else 0L
                require(start >= offsetMs) { "请先从歌曲开头重新准备兼容播放" }
                val takeTracks = snapshot()["tracks"] as List<*>
                player.stop(); player.seekTo(start - offsetMs)
                karaoke.start(JSONObject(song.toString()), server, JSONObject().put("startMs", start).put("pitch", pitch).put("speed", speed)
                    .put("channel", chain.channels.mode).put("revision", song.optString("revision"))
                    .put("resourceMode", resourceMode).put("tracks", org.json.JSONArray(takeTracks)).put("endMs", rangeB))
                chain.captureEnabled = true
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.prepare()
                if (!requestFocus()) error("无法取得音频焦点")
                player.play(); showRecordingForeground(); publish()
            } catch (e: Exception) { chain.captureEnabled = false; if (karaoke.phase == "RECORDING") finishRecording(e.message ?: "录唱启动失败"); message = e.message.orEmpty(); stopRecordingForeground(); publish() }
        }
        count()
    }
    fun finishRecording(reason: String, complete: Boolean = false) {
        if (countdown > 0) { countdownGeneration++; countdown = 0; stopRecordingForeground() }
        if (!::karaoke.isInitialized || karaoke.phase != "RECORDING") return
        chain.captureEnabled = false
        karaoke.finish(karaoke.takeId, reason, complete)
        player.pause(); stopRecordingForeground(); publish()
    }
    private fun showRecordingForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("karaoke-recording", "本机录唱", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7401, Intent(this, MainActivity::class.java).setAction("RETURN_SINGING"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(this, 7402, Intent(this, FinishTakeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "karaoke-recording") else { @Suppress("DEPRECATION") Notification.Builder(this) }
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(if (karaoke.phase == "RECORDING") "SingVerse · 正在录唱" else "SingVerse · 准备录唱")
            .setContentText(song.optString("title")).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "结束录唱", stop).build()).build()
        ServiceCompat.startForeground(this, 7401, notification, if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }
    private fun stopRecordingForeground() {
        if (destroyed) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onUpdateNotification(session, false)
    }
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (::karaoke.isInitialized && (karaoke.phase == "RECORDING" || countdown > 0)) showRecordingForeground()
        else super.onUpdateNotification(session, startInForegroundRequired)
    }
    fun playWork(id: String) {
        require(karaoke.phase !in listOf("RECORDING", "FINALIZING", "RENDERING")) { "请先结束当前录音" }
        val record = karaoke.works.get(id); val file = karaoke.works.playable(id)
        finishHistory("STOPPED")
        requestScope.cancel()
        generation++; cancelResource(leaseId); leaseId = ""; workId = id; resourceMode = "LOCAL"; rangeA = 0; rangeB = 0
        song = JSONObject(record.getJSONObject("song").toString()).put("title", record.optString("name")).put("mediaType", "AUDIO")
        originalDuration = record.optLong("durationMs"); offsetMs = 0; requestedPosition = 0; resourceReady = true; state = "BUFFERING"; message = ""
        pitch = 0; speed = 1f; player.playbackParameters = PlaybackParameters.DEFAULT
        player.setMediaItem(MediaItem.Builder().setUri(file.toURI().toString()).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(record.optString("name")).build()).build())
        player.prepare(); if (requestFocus()) player.play(); publish()
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focus, main).setWillPauseWhenDucked(true).build()
            audio.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            (audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        else { @Suppress("DEPRECATION") audio.abandonAudioFocus(focus) }
    }

    companion object {
        var current: PlaybackService? = null; private set
        var listener: ((Map<String, Any?>) -> Unit)? = null
        fun capabilities(): JSONObject = PlaybackCapabilities.snapshot()
    }
}
