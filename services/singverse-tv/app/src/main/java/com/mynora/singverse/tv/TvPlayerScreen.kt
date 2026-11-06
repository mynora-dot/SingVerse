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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
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
    var qualityOpen by remember { mutableStateOf(false) }
    var favoritesOpen by remember { mutableStateOf(false) }
    var cleanVideo by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val controlsVisible = audioOnly || app.controlVisible
    val playback = app.snapshot.optJSONObject("playback")
    val currentId = playback?.optString("songId").orEmpty()
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
        if (!controlsVisible && it.type == KeyEventType.KeyUp && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.DirectionDown, Key.DirectionUp)) {
            app.controlVisible = true
            true
        } else false
    }) {
        if (audioOnly) AudioPlayerScene(app, song) else VideoPlayerSurface(app)
        Box(Modifier.fillMaxSize().focusRequester(surfaceFocus).tvFocusTarget("player").focusable(enabled = !controlsVisible))
        PlayerHeader(app, audioOnly, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 26.dp, vertical = 15.dp))
        if (audioOnly || !cleanVideo) PlayerSideRail(app, Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 25.dp).width(196.dp))
        if (!audioOnly && !controlsVisible && app.playerState != "IDLE") VideoExternalLyrics(app)
        if (controlsVisible) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF20B0E14))))
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!audioOnly) CurrentVideoSong(app, song)
            PlayerControls(app, audioOnly, onQueue = { queueOpen = true }, onLyrics = { settings = "歌词字幕" },
                onOutput = { settings = "声音输出" }, onMore = { moreOpen = true }, primaryFocus = primary)
        }
    }
    if (queueOpen) Modal("已点歌曲", "当前演唱保持独立，这里只管理接下来的歌曲。", dismiss = { queueOpen = false }, wide = true) {
        Action("完成") { queueOpen = false }
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 292.dp)) { Queue(app, compact = true) }
    }
    if (moreOpen) Modal("更多设置", "播放辅助与本机操作。", { moreOpen = false }) {
        Action("完成") { moreOpen = false }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 278.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
            item { Action("音轨设置", Modifier.fillMaxWidth()) { settings = "音轨" } }
            item { Action("同步设置", Modifier.fillMaxWidth()) { settings = "同步" } }
            item { Action("音质详情", Modifier.fillMaxWidth()) { qualityOpen = true } }
            item { Action("本机收藏 · ${app.favoriteSongs().size}", Modifier.fillMaxWidth()) { favoritesOpen = true } }
            if (!audioOnly) item { Action(if (cleanVideo) "显示辅助卡片" else "纯净画面 · 隐藏辅助卡片", Modifier.fillMaxWidth(), selected = cleanVideo) { cleanVideo = !cleanVideo; moreOpen = false } }
            item { Action("刷新手机连接入口", Modifier.fillMaxWidth()) { if (!app.online) app.reconnect(); app.invite(); moreOpen = false } }
            item { Action("重试本机播放", Modifier.fillMaxWidth()) { app.retryPlayback(); moreOpen = false } }
            item { Action("停止本机播放", Modifier.fillMaxWidth(), danger = true) { app.stopLocal(); moreOpen = false } }
        }
    }
    settings?.let { tab -> Modal("播放设置", "更改只作用于当前播放链与本机设置。", dismiss = { settings = null }, wide = true) {
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
                        Text(favorite.artists(), color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1C1C1E), Ink), radius = 950f))) {
        Row(Modifier.fillMaxWidth().padding(start = 42.dp, end = 232.dp, top = 82.dp), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SongArtwork(app, song, Modifier.size(216.dp).clip(TvShape.panel), contentScale = ContentScale.Fit)
                app.audioQualityLabels.take(2).takeIf { it.isNotEmpty() }?.let { labels ->
                    Text(labels.joinToString(" · "), color = Muted, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(1f).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(song?.optString("title").orEmpty().ifBlank { "等待歌曲" }, color = Paper, fontSize = 33.sp, lineHeight = 41.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                song?.let { Text(it.artists(), color = Paper.copy(alpha = .62f), fontSize = 17.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                SongMetadata(app, song)
                if (app.hasConfirmedPlayback) AudioPlayerLyrics(app, Modifier.fillMaxWidth().padding(top = 8.dp))
                else Text(if (app.playerState == "ERROR") "播放失败，可在下方重试或切歌" else "确认开唱后显示歌词",
                    color = if (app.playerState == "ERROR") Danger else Muted, fontSize = 16.sp, lineHeight = 23.sp)
            }
        }
        PlaybackAmbience(app.playing && app.foreground, Modifier.align(Alignment.BottomCenter).padding(start = 223.dp, end = 223.dp, bottom = 154.dp).fillMaxWidth().height(20.dp))
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
            row.forEach { (label, value) -> Text("$label  $value", color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
        } }
    }
}

@Composable private fun CurrentVideoSong(app: TvApp, song: JSONObject?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SongArtwork(app, song, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(song?.optString("title").orEmpty().ifBlank { "等待演唱" }, color = Paper, fontSize = 18.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            song?.let { Text(it.artists(), color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        StatusPill(stateLabel(app.playerState), if (app.playerState == "ERROR") Danger else Primary)
    }
}

@Composable private fun PlayerHeader(app: TvApp, audioOnly: Boolean, modifier: Modifier) {
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(30000) } }
    val waiting = app.snapshot.optJSONObject("playback")?.optBoolean("confirmed") != true &&
        app.snapshot.optJSONArray("queue")?.objects()?.any { it.optString("status") == "HANDOFF" } == true
    val status = if (waiting) "等待确认开唱" else "${stateLabel(app.playerState)}${if (audioOnly) " · 音频" else ""}"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        HomeGlyph("brand", Modifier.size(23.dp), Primary)
        Text("SingVerse", color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        StatusPill(status, if (app.playerState == "ERROR" || !app.online) Danger else Primary)
        Spacer(Modifier.weight(1f))
        Text(clock, color = Paper, fontSize = 14.sp)
        Text(if (app.online) "NAS 已连接" else "网络异常", color = if (app.online) Muted else Danger, fontSize = 11.sp)
    }
}

@Composable private fun PlayerSideRail(app: TvApp, modifier: Modifier) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val playback = app.snapshot.optJSONObject("playback")
    val current = if (playback?.optBoolean("confirmed") == true) playback.optString("itemId")
        else queue.firstOrNull { it.optString("status") == "PLAYING" }?.optString("id").orEmpty()
    val next = tvNextQueuedItem(queue, { it.optString("status") }, { it.optString("id") }, current)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.End) {
        if (next != null) NextSongCard(app, next, Modifier.fillMaxWidth()) else Spacer(Modifier.height(65.dp))
        PlayerPhoneCard(app, Modifier.width(132.dp))
    }
}

@Composable private fun NextSongCard(app: TvApp, item: JSONObject, modifier: Modifier = Modifier) {
    val song = item.optJSONObject("song")
    val ending = app.duration > 0 && app.duration - app.position in 0..30000
    Row(modifier.background(Color(0xDE18181A), TvShape.card).border(1.dp, if (ending) Primary.copy(alpha = .75f) else Hairline, TvShape.card).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SongArtwork(app, song, Modifier.size(46.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("即将播放", color = if (ending) Primary else Muted, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(song?.optString("title").orEmpty(), color = Paper, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artists().orEmpty(), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun PlayerPhoneCard(app: TvApp, modifier: Modifier = Modifier) {
    Column(modifier.background(Color(0xE618181A), TvShape.card).border(1.dp, Hairline.copy(alpha = .65f), TvShape.card).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HomeGlyph("phone", Modifier.size(12.dp), Primary)
            Text("手机点歌", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (app.invitation.isNotBlank() && app.online) Qr(app.invitation, Modifier.size(100.dp))
        else Box(Modifier.size(100.dp).background(Panel, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(if (app.snapshot.optString("status") == "ENDED") "本场已结束" else if (app.invitationLoading) "生成邀请中…" else "更多设置中重试", color = Muted, fontSize = 10.sp)
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
        Text(status, color = if (app.online) Muted else Danger, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("同一网络扫码连接", color = Muted.copy(alpha = .7f), fontSize = 8.sp, lineHeight = 11.sp)
    }
}
