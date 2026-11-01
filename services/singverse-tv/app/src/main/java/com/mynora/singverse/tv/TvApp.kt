@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.net.URLEncoder
import java.util.UUID

class TvApp(context: android.content.Context) {
    val client = TvClient(context)
    val discovery = NasDiscovery(context)
    private val updater = TvUpdater(context, client)
    var updateStatus by mutableStateOf("检查 NAS 上发布的安装包")
    var updateManifest by mutableStateOf<JSONObject?>(null)
    var updateFile: java.io.File? = null
    fun checkUpdate() = run { updateFile=null;updateManifest=null;val release=json("/api/v1/tv/releases/current");updateManifest=release;updateStatus=if(release.optBoolean("available")) "可用版本 ${release.optString("versionName")} · ${release.optLong("size")/1048576} MB" else "NAS 尚未发布安装包" }
    fun downloadUpdate() = run { updateStatus="正在下载并核对签名…";updateFile=updater.download(updateManifest ?: return@run);updateStatus="大小、SHA-256、包名、版本与签名已核对，可确认安装" }
    fun installUpdate() = run { updater.install(updateFile ?: error("请先下载并校验安装包")) }
    private var transcodeId = ""
    private var timelineOffset = 0L
    private var sourceDuration = 0L
    private var resourceGeneration = 0
    var playbackQuality by mutableStateOf("auto")
    private var fallbackAttempted = false
    var online by mutableStateOf(false)
    var lastSync by mutableLongStateOf(0)
    private var failureCount = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var page by mutableStateOf("首页")
    var connected by mutableStateOf(false)
    var error by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var activeRequests = 0
    var snapshot by mutableStateOf(JSONObject())
    var homeSongs by mutableStateOf(listOf<JSONObject>())
    var homeTotal by mutableLongStateOf(0)
    var serverName by mutableStateOf("SingVerse")
    var artistQuery by mutableStateOf("")
    var artistPage by mutableIntStateOf(0)
    var artistTotal by mutableLongStateOf(0)
    private var artistGeneration = 0
    private var detailGeneration = 0
    private var lastLibraryRefresh = 0L
    var songs by mutableStateOf(listOf<JSONObject>())
    var tags by mutableStateOf(listOf<JSONObject>())
    var participants by mutableStateOf(listOf<JSONObject>())
    var selected by mutableStateOf<JSONObject?>(null)
    var invitation by mutableStateOf("")
    var address by mutableStateOf(client.base)
    var query by mutableStateOf("")
    var authorization by mutableStateOf<JSONObject?>(null)
    var selectedTags by mutableStateOf(setOf<String>())
    var unclassified by mutableStateOf(false)
    var number by mutableIntStateOf(0)
    var total by mutableLongStateOf(0)
    var artists by mutableStateOf(listOf<JSONObject>())
    var artistDetail by mutableStateOf<JSONObject?>(null)
    var lastArtistId by mutableStateOf("")
    private var artistDetailGeneration=0
    fun openArtist(artist: JSONObject) {
        val generation=++artistDetailGeneration;val id=artist.getString("id");lastArtistId=id
        run { val detail=json("/api/v1/tv/library/artists/$id");if(generation!=artistDetailGeneration)return@run;artistDetail=detail;selected=null;artistId=id;number=0;songs=emptyList();total=0;loadSongs() }
    }
    fun closeArtist() {artistDetailGeneration++;detailGeneration++;requestGeneration++;artistDetail=null;artistId="";selected=null}
    var playlists by mutableStateOf(listOf<JSONObject>())
    var playlistDetail by mutableStateOf<JSONObject?>(null)
    var playlistPage by mutableIntStateOf(0)
    var playlistTotal by mutableLongStateOf(0)
    var playlistQuery by mutableStateOf("")
    var lastPlaylistId by mutableStateOf("")
    private var playlistGeneration=0
    suspend fun fetchPlaylists(){val generation=++playlistGeneration;val result=json("/api/v1/tv/library/playlists?q=${URLEncoder.encode(playlistQuery,"UTF-8")}&page=$playlistPage&size=20");if(generation==playlistGeneration){playlists=result.getJSONArray("items").objects();playlistTotal=result.getLong("totalElements")}}
    fun loadPlaylists()=run {fetchPlaylists()}
    fun openPlaylist(playlist:JSONObject){val generation=++playlistGeneration;val id=playlist.getString("id");lastPlaylistId=id;run {val detail=json("/api/v1/tv/library/playlists/$id");if(generation!=playlistGeneration)return@run;playlistDetail=detail;artistId="";selected=null;number=0;songs=emptyList();total=0;loadSongs()}}
    fun closePlaylist(){playlistGeneration++;detailGeneration++;requestGeneration++;playlistDetail=null;selected=null}
    var artistId by mutableStateOf("")
    var lastSongId by mutableStateOf("")
    var lastSongPage by mutableStateOf("")
    private val commandsLock = Mutex()
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    var playerState by mutableStateOf("IDLE")
    var controlVisible by mutableStateOf(false)
    var pendingConfirm by mutableStateOf<String?>(null)
    var pendingOperation by mutableStateOf<JSONObject?>(null)
    private var loadedAttempt = ""
    private var foreground = true
    private var seq = 0L
    private var requestGeneration = 0
    private val dataSource = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
    private val channelProcessor = KaraokeAudioProcessor()
    private val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES).setAudioProcessors(arrayOf(channelProcessor)).build()
    }
    val player = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
    val playbackOptions = PlaybackOptions(context, player, channelProcessor)
    init {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { playbackOptions.refreshTracks(); if(selectedSourceId.isNotBlank() && playbackOptions.selectSource(selectedSourceId))selectedSourceId="" }
            override fun onPlaybackStateChanged(state: Int) {
                playerState = if(player.playerError!=null) "ERROR" else when (state) { Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_ENDED -> "ENDED"; Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"; else -> "IDLE" }
                if (state == Player.STATE_ENDED) controlVisible = true
                report()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (player.playbackState == Player.STATE_READY) playerState = if (isPlaying) "PLAYING" else "PAUSED"; report() }
            override fun onPlayerError(exception: PlaybackException) { if (!fallbackAttempted && connected && loadedAttempt.isNotBlank()) { fallbackAttempted = true; this@TvApp.run { rebuildPlayback(true) }; return }; playerState = "ERROR"; controlVisible = true; error = "此素材播放失败，可重试或跳过。${exception.errorCodeName}"; report() }
        })
        if (client.token.isNotEmpty()) run { connect() }
        scope.launch { while (isActive) { delay((2000L * (1 shl failureCount.coerceAtMost(3))).coerceAtMost(16000)); if (connected) refresh() } }
        scope.launch { while (isActive) { delay(10000); val id=transcodeId;if(connected && id.isNotBlank()) runCatching { json("/api/v1/tv/transcoding/$id") } } }
        scope.launch { var ticks=0; while (isActive) { delay(200); position = player.currentPosition.coerceAtLeast(0) + timelineOffset; duration = if (sourceDuration > 0) sourceDuration else player.duration.coerceAtLeast(0); playbackOptions.tick(position); if (++ticks % 25 == 0 && connected && loadedAttempt.isNotEmpty()) report() } }
    }
    private var selectedSourceId = ""
    private var requestedAudioRole=""
    private var backingResource=""
    private fun mediaItem(resource: JSONObject): MediaItem {
        val builder=MediaItem.Builder().setUri(client.base+resource.getString("url"))
        if(resource.has("subtitleUrl")) builder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(client.base+resource.getString("subtitleUrl"))).setMimeType(MimeTypes.TEXT_VTT).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        return builder.build()
    }
    private suspend fun loadResource(song: String, startMs: Long = 0, force: Boolean = false): JSONObject {
        val turn = ++resourceGeneration
        val old = transcodeId
        transcodeId = ""
        if (old.isNotBlank()) runCatching { json("/api/v1/tv/transcoding/$old/cancel", "POST") }
        val body = JSONObject().put("songId",song).put("client",PlaybackCapabilities.report()).put("quality",playbackQuality)
            .put("start",startMs/1000.0).put("force",force).put("audioRole",requestedAudioRole)
        var resource = json("/api/v1/tv/playback-resources", "POST", body)
        val initialAudio=resource.optJSONObject("audioRoles")
        backingResource=resource.optString("backingUrl")
        val executionId = resource.optString("id")
        val revision = resource.optString("revision")
        if (turn != resourceGeneration) {
            if(executionId.isNotBlank()) runCatching { json("/api/v1/tv/transcoding/$executionId/cancel","POST") }
            throw CancellationException("播放请求已替换")
        }
        transcodeId = executionId
        val deadline = android.os.SystemClock.elapsedRealtime() + 60000
        while (resource.optString("status") != "READY") {
            require(resource.optString("state") !in listOf("FAILED","CANCELLED")) { resource.optString("error","转换失败") }
            require(android.os.SystemClock.elapsedRealtime() < deadline) { "转换准备超时，请降低画质或生成兼容版本" }
            delay(500)
            if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
            resource = json("/api/v1/tv/transcoding/$executionId")
            if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
        }
        if (turn != resourceGeneration) throw CancellationException("播放请求已替换")
        timelineOffset = (resource.optDouble("offset",0.0)*1000).toLong()
        sourceDuration = ((resource.optJSONObject("execution")?.optJSONObject("media")?.optDouble("duration",0.0) ?: 0.0)*1000).toLong()
        resource.put("revision",revision)
        if(initialAudio!=null)resource.put("audioRoles",initialAudio)
        return resource
    }
    private fun setAudioResource(resource: JSONObject) {
        selectedSourceId=if(resource.optString("mode")=="DIRECT" && backingResource.isBlank())resource.optJSONObject("audioRoles")?.optString(if(requestedAudioRole=="original")"originalId" else if(requestedAudioRole=="backing")"backingId" else "").orEmpty() else ""
        val factory=DefaultMediaSourceFactory(dataSource)
        if(requestedAudioRole=="backing" && backingResource.isNotBlank()){
            val original=androidx.media3.exoplayer.source.FilteringMediaSource(factory.createMediaSource(mediaItem(resource)),setOf(androidx.media3.common.C.TRACK_TYPE_VIDEO,androidx.media3.common.C.TRACK_TYPE_TEXT))
            val backing=factory.createMediaSource(MediaItem.Builder().setUri(client.base+backingResource).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(timelineOffset).build()).build())
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(true,original,backing))
        }else player.setMediaItem(mediaItem(resource))
        resource.optJSONObject("audioRoles")?.let { playbackOptions.applyShared(it) }
    }
    fun saveAudioRole(original: Boolean)=run {
        val song=snapshot.optJSONObject("playback")?.optString("songId") ?: return@run
        val tracks=playbackOptions.tracks.filter { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO }
        val selected=tracks.firstOrNull { it.selected } ?: error("尚未识别音轨")
        if(tracks.size==1 && selected.channels==2 && playbackOptions.channel in listOf("LEFT","RIGHT")){playbackOptions.markRole(original);return@run}
        val other=tracks.firstOrNull { it.sourceId.isNotBlank() && it.sourceId!=selected.sourceId } ?: error("需要两条已识别源音轨")
        require(selected.sourceId.isNotBlank()) { "请先完成媒体探测" }
        val path="/api/v1/tv/audio-roles/songs/$song"
        val current=json(path)
        val saved=json(path,"PUT",JSONObject().put("revision",current.getLong("revision")).put("originalId",if(original)selected.sourceId else other.sourceId).put("backingId",if(original)other.sourceId else selected.sourceId))
        playbackOptions.applyShared(saved)
    }
    fun useAudioRole(original: Boolean)=run {
        val role=if(original)playbackOptions.roleOriginal else playbackOptions.roleBacking
        if(playbackOptions.isChannelRole(role)){require(playbackOptions.useRole(original)){"此版本的本机声道标记已失效"};return@run}
        requestedAudioRole=if(original)"original" else "backing";rebuildPlayback()
    }
    private suspend fun rebuildPlayback(force: Boolean = false, startMs: Long = position) {
        val song = snapshot.optJSONObject("playback")?.optString("songId")?.takeIf { it.isNotBlank() } ?: return
        val resource = loadResource(song,startMs,force)
        dataSource.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${client.token}"))
        setAudioResource(resource)
        playbackOptions.configureSource(resource.optJSONObject("execution")?.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("audioRoles")?.optJSONArray("streams"),resource.optJSONObject("execution")?.optJSONArray("trackMapping"), resource.optString("mode")=="DIRECT")
        if (resource.optString("mode") != "HLS") player.seekTo(startMs)
        player.prepare(); player.play()
    }
    fun changeQuality(quality: String) { playbackQuality=quality; run { rebuildPlayback() } }
    fun seekAbsolute(positionMs: Long) { if (transcodeId.isNotBlank()) run { rebuildPlayback(false,positionMs.coerceAtLeast(0)) } else player.seekTo(positionMs.coerceAtLeast(0)) }
    fun run(task: suspend () -> Unit) { scope.launch { activeRequests++; busy = true; try { task() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "连接失败，请重试" } finally { activeRequests--; busy = activeRequests > 0 } } }
    private fun acceptSnapshot(value: JSONObject, replaceSession: Boolean = false) {
        if(!replaceSession && snapshot.optString("id").isNotBlank() && value.optString("id")!=snapshot.optString("id"))return
        if(value.optString("id")==snapshot.optString("id") && value.has("stateRevision") && snapshot.has("stateRevision") && value.getLong("stateRevision")<snapshot.getLong("stateRevision"))return
        snapshot=value
    }
    private suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val server=client.base;val credential=client.token
        val result=JSONObject(client.request(path,method,body))
        if(client.base!=server || client.token!=credential)throw CancellationException("连接已更换")
        return result
    }
    fun connect() = run { val info = json("/api/v1/server-info"); require(info.optString("service") == "singverse" && info.optInt("apiVersion") == 1) { "不是兼容的 SingVerse 服务" }; acceptSnapshot(json("/api/v1/tv/sessions", "POST"), replaceSession=true); if (!connected) { acceptSnapshot(json("/api/v1/tv/sessions/${snapshot.getString("id")}/recover", "POST")); page = "首页" }; connected = true; online = true; failureCount = 0; error = ""; loadHome(); loadSongs(); loadTags() }
    fun beginAuthorization() = run {
        client.clear(); client.save(address, "")
        val info = json("/api/v1/server-info"); require(info.optString("service") == "singverse") { "不是 SingVerse 服务" }
        authorization = json("/api/v1/device-authorizations", "POST", JSONObject().put("name", android.os.Build.MODEL.take(60)))
        val authorizationStarted=android.os.SystemClock.elapsedRealtime()
        while (authorization != null && !connected) {
            delay(2500); val a = authorization ?: break
            if(android.os.SystemClock.elapsedRealtime()-authorizationStarted>600000) { authorization=null;error("授权码已过期，请重新连接") }
            val result = try { json("/api/v1/device-authorizations/${a.getString("id")}/poll", "POST", JSONObject().put("pollSecret", a.getString("pollSecret"))) } catch(e: TvHttpException) { if(e.status in listOf(401,404,410))authorization=null;throw e }
            if(authorization?.optString("id")!=a.optString("id"))return@run
            if (result.optString("status") == "DENIED") { authorization = null; error("主人拒绝了本次连接，可核对 NAS 后重新申请") }
            if (result.optString("status") == "APPROVED") { client.save(address, result.getString("token")); authorization = null; connect(); break }
        }
    }
    suspend fun refresh() { try { val sid = snapshot.optString("id"); if (sid.isEmpty()) return; acceptSnapshot(json("/api/v1/tv/sessions/$sid/snapshot")); if(error.startsWith("与 NAS 同步失败"))error=""; online = true; failureCount = 0; lastSync = android.os.SystemClock.elapsedRealtime(); if (foreground) executeCommands(); if (page == "设置") participants = JSONArray(client.request("/api/v1/tv/sessions/$sid/participants")).objects()
        if (foreground && android.os.SystemClock.elapsedRealtime() - lastLibraryRefresh > 30000) {
            if (page == "首页") loadHome()
            if (page == "分类") loadTags()
            if (page == "歌单") {
                val id=playlistDetail?.optString("id")
                if(id==null)fetchPlaylists() else {val generation=playlistGeneration;try{val detail=json("/api/v1/tv/library/playlists/$id");if(generation==playlistGeneration&&playlistDetail?.optString("id")==id){playlistDetail=detail;if(selected==null)loadSongs()}}catch(e:TvHttpException){if(e.status==404&&generation==playlistGeneration){closePlaylist();fetchPlaylists()}else throw e}}
            }
            if (page == "歌手") {
                val id=artistDetail?.optString("id")
                if(id==null)fetchArtists() else {
                    val generation=artistDetailGeneration
                    try {
                        val detail=json("/api/v1/tv/library/artists/$id")
                        if(generation==artistDetailGeneration&&artistDetail?.optString("id")==id){artistDetail=detail;if(selected==null)loadSongs()}
                    }catch(e:TvHttpException){if(e.status==404&&generation==artistDetailGeneration){closeArtist();fetchArtists()}else throw e}
                }
            }
            lastLibraryRefresh = android.os.SystemClock.elapsedRealtime()
        } } catch (e: Exception) { if (e is CancellationException) throw e; online = false; failureCount++; if (e is TvHttpException && e.status == 401) { pauseLocal(); client.clear(); connected = false; authorization = null }; error = "与 NAS 同步失败；本地仍可暂停或停止。${e.message}" } }
    suspend fun loadHome() {
        val server = client.base; val credential = client.token
        val result = json("/api/v1/tv/home")
        if (client.base != server || client.token != credential) return
        homeSongs = result.getJSONArray("recentSongs").objects()
        homeTotal = result.getLong("totalSongs")
        serverName = result.getJSONObject("server").optString("name", "SingVerse")
    }
    suspend fun loadTags() {
        val server = client.base; val credential = client.token
        val result = JSONArray(client.request("/api/v1/tv/library/classification-tags")).objects()
        if (client.base == server && client.token == credential) tags = result
    }
    fun navigate(destination: String) {
        if(playlistDetail!=null)closePlaylist()
        playlistGeneration++
        if(artistDetail!=null)closeArtist()
        artistDetailGeneration++
        detailGeneration++
        selected = null
        page = destination
        run {
            when (destination) {
                "首页" -> loadHome()
                "找歌" -> loadSongs()
                "歌手" -> fetchArtists()
                "分类" -> loadTags()
                "歌单" -> fetchPlaylists()
                "已点", "设置" -> refresh()
            }
        }
    }
    fun openSong(song: JSONObject) {
        val generation = ++detailGeneration
        val songId = song.getString("id")
        val sourcePage = page
        lastSongId = songId; lastSongPage = sourcePage
        run {
            val detail = json("/api/v1/tv/library/songs/$songId")
            if (generation == detailGeneration && page == sourcePage) selected = detail
        }
    }
    private suspend fun fetchArtists() {
        val generation = ++artistGeneration
        val result = json("/api/v1/tv/library/artists?q=${URLEncoder.encode(artistQuery, "UTF-8")}&page=$artistPage&size=20")
        if (generation == artistGeneration) { artists = result.getJSONArray("items").objects(); artistTotal = result.getLong("totalElements") }
    }
    fun loadArtists() = run { fetchArtists() }
    suspend fun loadSongs() {
        val generation = ++requestGeneration
        val filter = selectedTags.joinToString("") { "&tagIds=${URLEncoder.encode(it, "UTF-8")}" }
        val path = if (playlistDetail!=null) "/api/v1/tv/library/playlists/${playlistDetail!!.getString("id")}/songs?page=$number&size=20" else if (artistId.isNotEmpty()) "/api/v1/tv/library/artists/$artistId/songs?page=$number&size=20" else "/api/v1/tv/library/songs?q=${URLEncoder.encode(query, "UTF-8")}&page=$number&size=20$filter&unclassified=$unclassified"
        val result = json(path)
        if (generation == requestGeneration) { songs = result.getJSONArray("items").objects(); total = result.getLong("totalElements") }
    }
    fun search(reset: Boolean = true) = run { if (reset) number = 0; selected = null; loadSongs() }
    fun command(action: String, itemId: String? = null, songId: String? = null, participantId: String? = null) = run {
        check(online) { "NAS 离线，先重连核对状态；本机暂停/停止仍可使用" }
        check(pendingOperation == null) { "上次操作结果未知，请先核对或重试原请求" }
        val body = JSONObject().put("action", action).put("requestId", UUID.randomUUID().toString()).put("expectedRevision", snapshot.optLong("revision"))
        if (itemId != null) body.put("itemId", itemId)
        if (songId != null) body.put("songId", songId)
        if (participantId != null) body.put("participantId", participantId)
        pendingOperation = body
        try { acceptSnapshot(json("/api/v1/tv/sessions/${snapshot.getString("id")}/commands", "POST", body)); pendingOperation = null; error = ""; executeCommands() } catch (e: Exception) { if(e is TvHttpException && e.status in 400..499)pendingOperation=null; refresh(); throw e }
    }
    fun discardPending() { pendingOperation = null }
    fun reconnect() = run { refresh() }
    fun retryPlayback() { player.prepare(); player.play() }
    fun reloadText() = run { val songId = snapshot.optJSONObject("playback")?.optString("songId") ?: return@run; loadText(songId) }
    private suspend fun loadText(songId: String) { try { playbackOptions.applyText(songId, json("/api/v1/tv/library/songs/$songId/text-tracks")) } catch (e: Exception) { if (e is CancellationException) throw e; playbackOptions.textFailed(songId) } }
    fun retryPending() = run { val body = pendingOperation ?: return@run; acceptSnapshot(json("/api/v1/tv/sessions/${snapshot.getString("id")}/commands", "POST", body)); pendingOperation = null; executeCommands() }
    private suspend fun executeCommands() = commandsLock.withLock {
        for (c in snapshot.optJSONArray("commands")?.objects().orEmpty()) {
            val action = c.getString("action")
            val p = c.optJSONObject("playback")
            if (action == "LOAD" || action == "RESTART") {
                if (p != null && loadedAttempt != p.getString("attemptId")) {
                    requestedAudioRole=""; val resource = loadResource(p.getString("songId")); fallbackAttempted = false
                    player.stop(); loadedAttempt = p.getString("attemptId"); seq = 0
                    dataSource.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${client.token}"))
                    setAudioResource(resource)
                    playbackOptions.beginSong(p.getString("songId"), resource.getString("revision"))
                    playbackOptions.configureSource(resource.optJSONObject("execution")?.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("media")?.optJSONArray("streams") ?: resource.optJSONObject("audioRoles")?.optJSONArray("streams"),resource.optJSONObject("execution")?.optJSONArray("trackMapping"), resource.optString("mode")=="DIRECT")
                    resource.optJSONObject("audioRoles")?.let { playbackOptions.applyShared(it) }
                    player.prepare(); player.play(); page = "演唱"; controlVisible = false
                    scope.launch { loadText(p.getString("songId")) }
                }
            } else when (action) { "PAUSE" -> player.pause(); "RESUME" -> player.play(); "STOP" -> { stopLocal(); loadedAttempt = ""; playerState = "IDLE" } }
            acceptSnapshot(json("/api/v1/tv/sessions/${snapshot.getString("id")}/playback-events", "POST", JSONObject().put("commandId", c.getString("id"))))
        }
    }
    fun report() {
        if (!connected || loadedAttempt.isEmpty()) return
        val attempt = loadedAttempt; val sequence = ++seq; val state = playerState; val pos = player.currentPosition.coerceAtLeast(0) + timelineOffset
        scope.launch { runCatching { json("/api/v1/tv/sessions/${snapshot.getString("id")}/playback-events", "POST", JSONObject().put("attemptId", attempt).put("eventSeq", sequence).put("state", state).put("positionMs", pos)) } }
    }
    fun foreground() { foreground = true;playbackOptions.refreshRoutes();if(connected)reconnect() }
    fun background() { foreground = false; pauseLocal() }
    fun pauseLocal() { player.pause(); report() }
    fun stopLocal() { resourceGeneration++; val old=transcodeId;transcodeId="";if(old.isNotBlank())run { json("/api/v1/tv/transcoding/$old/cancel","POST") }; player.stop(); playerState = "IDLE"; report() }
    fun invite() = run { val n = json("/api/v1/tv/sessions/${snapshot.getString("id")}/invitations", "POST"); invitation = client.base + "/party/#invite=" + n.getString("invitation") }
    fun disconnect() {playlistGeneration++;playlistDetail=null;playlists=emptyList();playlistPage=0;playlistQuery="";playlistTotal=0;lastPlaylistId=""; stopLocal();loadedAttempt="";pendingOperation=null;updateManifest=null;updateFile=null;online=false; client.clear(); connected = false; authorization = null; snapshot = JSONObject(); artistDetail=null;artistDetailGeneration++;lastArtistId=""; invitation = ""; selected = null; homeSongs = emptyList(); songs = emptyList(); artists = emptyList(); tags = emptyList(); participants = emptyList(); homeTotal = 0; artistTotal = 0; total = 0; query = ""; artistQuery = ""; number = 0; artistPage = 0; artistId = ""; selectedTags = emptySet(); unclassified = false; requestGeneration++; artistGeneration++; detailGeneration++ }
    fun newSession() = run { acceptSnapshot(json("/api/v1/tv/sessions", "POST"), replaceSession=true); invitation = "" }
    fun close() { resourceGeneration++; discovery.stop(); playbackOptions.close(); scope.cancel(); player.release() }
}
