@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RestartControlFocus(val attemptId: String, val songId: String, val navigationVersion: Int)

/** Owns only presentation. Media3, queue commands and original media timelines stay in TvApp. */
@Composable internal fun PlayerScreen(app: TvApp) {
    val audioOnly = app.playerMode == TvPlayerMode.AUDIO
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val primary = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    var queueOpen by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    val moreList = rememberLazyListState()
    var moreReturnIndex by remember { mutableStateOf<Int?>(null) }
    var qualityOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var cleanVideo by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var navigationVersion by remember { mutableIntStateOf(0) }
    var restartControlFocus by remember { mutableStateOf<RestartControlFocus?>(null) }
    val controlsVisible = audioOnly || app.controlVisible
    val playback = app.snapshot.optJSONObject("playback")
    val currentId = playback?.optString("songId").orEmpty()
    val currentAttempt = playback?.optString("attemptId").orEmpty()
    val song = app.currentSong?.takeIf { it.optString("id") == currentId }
        ?: app.snapshot.optJSONArray("queue")?.objects()?.firstOrNull { it.optString("songId") == currentId || it.optJSONObject("song")?.optString("id") == currentId }?.optJSONObject("song")

    LaunchedEffect(audioOnly) { if (audioOnly) app.controlVisible = true }
    LaunchedEffect(controlsVisible, audioOnly, currentId) {
        withFrameNanos { }; withFrameNanos { }
        if (memory.activeDialogs == 0) {
            if (controlsVisible) {
                if (!memory.restore(page) || memory.last[page] == "player") primary.requestFocus()
            } else surfaceFocus.requestFocus()
        }
    }
    LaunchedEffect(restartControlFocus, currentAttempt, currentId, app.playerState, app.busy, memory.activeDialogs, controlsVisible, navigationVersion, app.error) {
        val origin = restartControlFocus ?: return@LaunchedEffect
        if (currentId != origin.songId || !controlsVisible || navigationVersion != origin.navigationVersion || app.error.isNotBlank()) {
            restartControlFocus = null
            return@LaunchedEffect
        }
        if (currentAttempt == origin.attemptId || app.busy || memory.activeDialogs > 0 || !app.hasConfirmedPlayback ||
            app.playerState !in listOf("PLAYING", "PAUSED")) return@LaunchedEffect
        // Restart briefly disables its control while the new attempt is prepared. Keep
        // that intentional source unless the user has moved elsewhere with the remote.
        withFrameNanos { }; withFrameNanos { }
        if (restartControlFocus == origin && memory.activeDialogs == 0 && navigationVersion == origin.navigationVersion) {
            memory.last[page] = "action:player:restart"
            memory.restore(page)
            restartControlFocus = null
        }
    }
    LaunchedEffect(controlsVisible, audioOnly, app.playing, app.foreground, lastInteraction, memory.activeDialogs) {
        if (!audioOnly && controlsVisible && app.playing && app.foreground && memory.activeDialogs == 0) {
            delay(8000)
            app.controlVisible = false
        }
    }
    BackHandler(enabled = memory.activeDialogs == 0 && app.pendingConfirm == null && app.error.isBlank()) {
        if (!audioOnly && controlsVisible) app.controlVisible = false else app.navigate("首页")
    }
    Box(Modifier.fillMaxSize().background(Ink).semantics {
        stateDescription = if (audioOnly) "音频播放器" else "视频播放器"
    }.onPreviewKeyEvent {
        lastInteraction = SystemClock.elapsedRealtime()
        if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)) navigationVersion++
        if (!controlsVisible && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.DirectionDown, Key.DirectionUp)) {
            app.controlVisible = true
            true
        } else false
    }) {
        if (audioOnly) AudioPlayerScene(app, song) else VideoPlayerSurface(app)
        Box(Modifier.fillMaxSize().focusRequester(surfaceFocus).tvFocusTarget("player").focusable(enabled = !controlsVisible))
        PlayerHeader(app, audioOnly, controlsVisible, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp))
        if (audioOnly || !cleanVideo) PlayerSideRail(app, Modifier.align(Alignment.CenterEnd).padding(top = 20.dp, end = 28.dp, bottom = 108.dp).width(160.dp))
        if (!audioOnly && controlsVisible) CurrentVideoSong(app, song, Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 82.dp, end = 232.dp))
        if (!audioOnly && !controlsVisible && app.playerState != "IDLE") VideoExternalLyrics(app)
        if (controlsVisible) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 36.dp)
                .background(Panel.copy(alpha = if (audioOnly) 1f else .94f), TvShape.card).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerControls(app, audioOnly, onQueue = { queueOpen = true }, onLyrics = { settings = "歌词字幕" },
                onOutput = { settings = "声音输出" }, onMore = { moreOpen = true },
                onRestart = { restartControlFocus = RestartControlFocus(currentAttempt, currentId, navigationVersion) }, primaryFocus = primary)
        }
        if (controlsVisible) Text("方向键选择 · 确认操作 · 返回键返回", color = Muted, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
    if (queueOpen) Modal("接下来", "当前演唱保持独立，这里只管理待唱歌曲", dismiss = { queueOpen = false }, wide = true) {
        Action("完成") { queueOpen = false }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 292.dp)) { Queue(app, compact = true) }
    }
    if (moreOpen) Modal("更多设置", "播放辅助与本机操作", { moreOpen = false }) {
        val dialogPage = LocalTvFocusPage.current
        val returnKeys = listOf("音轨与声道", if (audioOnly) "歌词与声音同步" else "画质与同步", "音质详情", "本机收藏 · ${app.favoriteSongs().size}")
        LaunchedEffect(memory.dialogs.lastOrNull(), memory.restoreVersion) {
            val index = moreReturnIndex ?: return@LaunchedEffect
            if (memory.dialogs.lastOrNull() != dialogPage) return@LaunchedEffect
            // A covered Android dialog can scroll its lazy content while focus is suspended.
            // Compose the actual source row before asking the shared modal to restore it.
            memory.restoring = true
            moreList.scrollToItem(index)
            withFrameNanos { }; withFrameNanos { }
            memory.last[dialogPage] = "action:${returnKeys[index]}"
            memory.restore(dialogPage)
            memory.restoring = false
            moreReturnIndex = null
        }
        Action("完成") { moreOpen = false }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 278.dp), state = moreList, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
            item("tracks") { Action(returnKeys[0], Modifier.fillMaxWidth()) { moreReturnIndex = 0; settings = "音轨" } }
            item("synchronization") { Action(returnKeys[1], Modifier.fillMaxWidth()) { moreReturnIndex = 1; settings = "同步" } }
            item("quality") { Action(returnKeys[2], Modifier.fillMaxWidth()) { moreReturnIndex = 2; qualityOpen = true } }
            item("favorites") { Action(returnKeys[3], Modifier.fillMaxWidth()) { moreReturnIndex = 3; favoritesOpen = true } }
            if (!audioOnly) item { Action(if (cleanVideo) "显示辅助卡片" else "纯净画面 · 隐藏辅助卡片", Modifier.fillMaxWidth(), selected = cleanVideo) { cleanVideo = !cleanVideo; moreOpen = false } }
            item { Action("重新读取歌词 / 字幕", Modifier.fillMaxWidth()) { app.reloadText(); moreOpen = false } }
            item { Action("刷新手机邀请", Modifier.fillMaxWidth()) { if (!app.online) app.reconnect(); app.invite(); moreOpen = false } }
            item { Action("重试本机播放", Modifier.fillMaxWidth()) { app.retryPlayback(); moreOpen = false } }
            item { Action("停止本机播放", Modifier.fillMaxWidth(), danger = true) { app.stopLocal(); moreOpen = false } }
        }
    }
    settings?.let { tab -> Modal("播放设置", "根据当前歌曲与设备显示可用选项", dismiss = { settings = null }, wide = true) {
        Action("完成") { settings = null }
        PlaybackSettings(app, initialTab = tab, audioOnly = audioOnly, modifier = Modifier.weight(1f, fill = false))
    } }
    if (qualityOpen) Modal("音质详情", "依据当前实际解码音轨；未提供的参数不显示。", { qualityOpen = false }) {
        val labels = app.audioQualityLabels
        if (labels.isEmpty()) Text("正在读取音轨信息", color = Muted, fontSize = 16.sp, lineHeight = 22.sp)
        else labels.forEachIndexed { index, label ->
            Row(Modifier.fillMaxWidth().background(Panel, TvShape.control).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(label, color = if (index == 0) Primary else Paper, fontSize = 16.sp, lineHeight = 22.sp)
            }
        }
        Action("完成") { qualityOpen = false }
    }
    if (favoritesOpen) Modal("本机收藏", "仅保存在这台电视，按 NAS 分开记录。", { favoritesOpen = false }, wide = true) {
        Action("完成") { favoritesOpen = false }
        val favorites = app.favoriteSongs()
        if (favorites.isEmpty()) EmptyState("还没有收藏歌曲", "播放歌曲时按「收藏」，下次可从这里快速加入已点。")
        else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
            items(favorites, key = { it.optString("id") }) { favorite ->
                Row(Modifier.fillMaxWidth().background(Panel, TvShape.card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SongArtwork(app, favorite, Modifier.size(50.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(favorite.optString("title"), color = Paper, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(favorite.artists(), color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Action("加入已点", focusKey = "favorite:${favorite.optString("id")}") { app.command("ADD", songId = favorite.optString("id")) }
                }
            }
        }
    }
}

@Composable private fun VideoPlayerSurface(app: TvApp) {
    AndroidView(factory = { context -> PlayerView(context).apply { player = app.player; useController = false; isFocusable = false } },
        modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
}

@Composable private fun VideoExternalLyrics(app: TvApp) {
    val line = app.playbackOptions.activeLine ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val text = buildAnnotatedString {
            if (!line.hasCompleteWords) append(line.text)
            else line.words.forEach { word -> withStyle(SpanStyle(color = if (word.at <= app.playbackOptions.lyricPosition) Primary else Paper)) { append(word.text) } }
        }
        Text(text, color = Paper, fontSize = 30.sp, lineHeight = 40.sp, maxLines = 3,
            modifier = Modifier.padding(40.dp).background(Color.Black.copy(alpha = .64f), TvShape.control).padding(16.dp))
    }
}

@Composable private fun AudioPlayerScene(app: TvApp, song: JSONObject?) {
    Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 210.dp, top = 80.dp, bottom = 144.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SongArtwork(app, song, Modifier.size(200.dp).clip(TvShape.card), contentScale = ContentScale.Fit)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song?.optString("title").orEmpty().ifBlank { "等待歌曲" }, color = Paper, fontSize = 24.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                song?.let { Text(it.artists(), color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (app.hasConfirmedPlayback) AudioPlayerLyrics(app, Modifier.fillMaxWidth())
            else Text(if (app.playerState == "ERROR") "播放失败，可在下方重试或切歌" else "确认开唱后显示歌词",
                color = if (app.playerState == "ERROR") Danger else Muted, fontSize = 18.sp, lineHeight = 28.sp)
            SongMetadata(app, song)
        }
    }
}

@Composable private fun SongMetadata(app: TvApp, song: JSONObject?) {
    if (song == null) return
    val ids = song.optJSONObject("classification")?.optJSONObject("dimensions")?.optJSONObject("GENRE")?.optJSONArray("tagIds")
    val genreIds = ids?.let { (0 until it.length()).map { index -> it.optString(index) }.toSet() }.orEmpty()
    val genres = app.tags.filter { it.optString("id") in genreIds }.map { it.optString("name") }
    val metadata = tvSongMetadataLabels(song.optString("album"), song.optString("releaseDate"), genres, song.optLong("durationMs").takeIf { it > 0 } ?: app.duration)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        metadata.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { (label, value) -> Text("$label  $value", color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
        } }
    }
}

@Composable private fun CurrentVideoSong(app: TvApp, song: JSONObject?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SongArtwork(app, song, Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song?.optString("title").orEmpty().ifBlank { "等待演唱" }, color = Paper, fontSize = 22.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            song?.let { Text(it.artists(), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        StatusPill(stateLabel(app.playerState), if (app.playerState == "ERROR") Danger else Primary)
    }
}

@Composable private fun PlayerHeader(app: TvApp, audioOnly: Boolean, controlsVisible: Boolean, modifier: Modifier) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(30000) } }
    val waiting = app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true &&
        app.snapshot.optJSONArray("queue")?.objects()?.any { it.optString("status") == "HANDOFF" } == true
    val status = if (waiting) "等待确认开唱" else "${stateLabel(app.playerState)}${if (audioOnly) " · 音频" else ""}"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (controlsVisible) Action("返回点歌", focusKey = "player:return-catalog", textSize = 14.sp) { app.navigate("点歌") }
        HomeGlyph("brand", Modifier.size(20.dp), Primary)
        Text("SingVerse", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        StatusPill(status, if (app.playerState == "ERROR" || !app.online) Danger else Primary)
        Spacer(Modifier.weight(1f))
        Text(clock, color = Paper, fontSize = 14.sp)
        Text(if (app.online) "NAS 已连接" else "网络异常", color = if (app.online) Muted else Danger, fontSize = 12.sp)
    }
}

@Composable private fun PlayerSideRail(app: TvApp, modifier: Modifier) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val playback = app.snapshot.optJSONObject("playback")
    val current = if (playback?.optBoolean("confirmed") == true) playback.optString("itemId")
        else queue.firstOrNull { it.optString("status") == "PLAYING" }?.optString("id").orEmpty()
    val next = tvNextQueuedItem(queue, { it.optString("status") }, { it.optString("id") }, current)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.End) {
        if (next != null) NextSongCard(app, next, Modifier.fillMaxWidth())
        PlayerPhoneCard(app, Modifier.fillMaxWidth())
    }
}

@Composable private fun NextSongCard(app: TvApp, item: JSONObject, modifier: Modifier = Modifier) {
    val song = item.optJSONObject("song")
    val ending = app.duration > 0 && app.duration - app.position in 0..30000
    Row(modifier.background(Panel.copy(alpha = .94f), TvShape.card).border(1.dp, if (ending) Primary.copy(alpha = .75f) else Hairline, TvShape.card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SongArtwork(app, song, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("即将播放", color = if (ending) Primary else Muted, fontSize = 12.sp, lineHeight = 16.sp)
            Text(song?.optString("title").orEmpty(), color = Paper, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artists().orEmpty(), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun PlayerPhoneCard(app: TvApp, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel.copy(alpha = .94f), TvShape.card).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HomeGlyph("phone", Modifier.size(14.dp), Muted)
            Text("手机点歌", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (app.invitation.isNotBlank() && app.online) Qr(app.invitation, Modifier.size(128.dp))
        else Box(Modifier.size(128.dp).background(Panel, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成邀请中…" else "更多设置中重试", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        val status = when {
            app.snapshot.optString("status") == "ENDED" -> "返回首页可新开场"
            !app.online -> "网络异常"
            app.invitationError.isNotBlank() -> "邀请暂不可用"
            app.invitationLoading -> "正在生成邀请"
            !app.participantsKnown -> "正在同步设备"
            app.participantCount == 0 -> "等待亲友加入"
            else -> "已加入 ${app.participantCount} 台"
        }
        Text(status, color = if (app.online) Muted else Danger, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("同一网络扫码连接", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
