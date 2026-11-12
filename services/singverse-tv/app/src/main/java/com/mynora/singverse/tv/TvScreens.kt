@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mynora.singverse.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.launch
import org.json.*

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun stateLabel(value: String) = mapOf("IDLE" to "已停止", "PREPARING" to "准备中", "BUFFERING" to "缓冲中", "PLAYING" to "正在演唱", "PAUSED" to "已暂停", "ENDED" to "演唱结束", "ERROR" to "播放失败", "WAITING" to "待唱", "HANDOFF" to "等待确认", "COMPLETED" to "已唱", "SKIPPED" to "已跳过", "FAILED" to "播放失败", "CANCELLED" to "已撤回")[value] ?: "等待同步"
internal fun versionLabel(value: String) = mapOf("ORIGINAL" to "原唱", "ACCOMPANIMENT" to "伴奏", "INSTRUMENTAL" to "纯音乐", "DUET" to "合唱", "KARAOKE" to "卡拉 OK")[value] ?: "版本待确认"
internal fun time(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable internal fun Modal(
    title: String,
    subtitle: String = "",
    dismiss: () -> Unit,
    wide: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val memory = LocalTvFocusMemory.current
    val sourcePage = LocalTvFocusPage.current
    val parentFocus = LocalFocusManager.current
    val dialog = remember(title) { "dialog:${java.util.UUID.randomUUID()}:$title" }
    // Player sheets are sibling composables, but their focus parent is the
    // already-open sheet. Capture that stack once, before registering this one.
    val parent = remember(memory, dialog) { memory.dialogs.lastOrNull() ?: sourcePage }
    val returnTarget = remember(memory, parent, dialog) {
        memory.restoring = true
        memory.directionalInput = false
        memory.last[parent]
    }
    DisposableEffect(memory, parent, dialog) {
        memory.restoring = true
        memory.dialogs.add(dialog)
        parentFocus.clearFocus(force = true)
        onDispose {
            memory.dialogs.remove(dialog)
            memory.last.remove(dialog)
            if (returnTarget != null) memory.last[parent] = returnTarget
            memory.restoring = true
            memory.restoreVersion++
        }
    }
    // Dialog owns a separate Android window, whose platform density otherwise replaces
    // the logical TV canvas density. Keep text and controls at the same scale as the player.
    val canvasDensity = LocalDensity.current
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalTvFocusPage provides dialog, LocalDensity provides canvasDensity) {
            val entry = remember { FocusRequester() }
            LaunchedEffect(dialog, memory.dialogs.lastOrNull(), memory.restoreVersion) {
                withFrameNanos { }; withFrameNanos { }
                if (memory.dialogs.lastOrNull() != dialog) return@LaunchedEffect
                memory.restoring = false
                if (!memory.restore(dialog)) {
                    val preferred = listOf("取消", "完成", "关闭提示", "返回").firstNotNullOfOrNull { memory.targets["$dialog:action:$it"] }
                    if (preferred != null) preferred.requestFocus() else entry.requestFocus()
                }
            }
            Column(
                Modifier.width(if (wide) 760.dp else 500.dp).heightIn(max = 462.dp)
                    .focusRequester(entry).focusGroup().background(Panel, TvShape.dialog).padding(30.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, color = Paper, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                    if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                content()
            }
        }
    }
}

@Composable internal fun TvScreen(app: TvApp, focusMemory: TvFocusMemory = remember(app.client.base) { TvFocusMemory() }) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val density = LocalDensity.current
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            TvScreenContent(app, focusMemory)
        }
    }
}

@Composable private fun TvScreenContent(app: TvApp, memory: TvFocusMemory) {
    val view = when {
        app.selected != null -> "${app.page}:song:${app.selected?.optString("id")}"
        app.playlistDetail != null -> "${app.page}:playlist:${app.playlistDetail?.optString("id")}"
        app.artistDetail != null -> "${app.page}:artist:${app.artistDetail?.optString("id")}"
        else -> app.page
    }
    if (memory.view != view) { memory.view = view; memory.restoring = true; memory.directionalInput = false }
    CompositionLocalProvider(LocalTvFocusMemory provides memory, LocalTvFocusPage provides view) {
        ConnectedTvContent(app, memory, view)
    }
}

@Composable private fun ConnectedTvContent(app: TvApp, memory: TvFocusMemory, view: String) {
    var invitationOpen by remember { mutableStateOf(false) }
    val librarySongs = rememberLazyListState()
    val playlistsGrid = rememberLazyGridState()
    val playlistSongs = rememberLazyListState()
    val artistsGrid = rememberLazyGridState()
    val categoriesGrid = rememberLazyGridState()
    var categoryDimension by remember { mutableStateOf("LANGUAGE") }
    val artistSongs = rememberLazyListState()
    val feedList = rememberLazyListState()
    var handledFeedReset by remember { mutableIntStateOf(app.feedResetVersion) }
    var handledSongListReset by remember { mutableIntStateOf(app.songListResetVersion) }
    val homeFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val selectedNavigation = when {
        app.selected != null || app.page in listOf("找歌", "歌手", "分类", "歌单", "点歌") -> "点歌"
        app.page in listOf("热歌", "历史") -> "首页"
        else -> app.page
    }
    fun selectNavigation(destination: String) {
        if (app.page == destination && app.selected == null && app.artistDetail == null && app.playlistDetail == null) return
        // Selecting a tab must keep focus in the navigation, even if its page remembers content focus.
        memory.last[destination] = "nav:$destination"
        app.navigate(destination)
    }
    LaunchedEffect(app.connected, view, memory.restoreVersion, windowFocused, app.songListResetVersion, app.feedResetVersion) {
        if (app.connected && memory.activeDialogs == 0 && windowFocused) {
            val navigation = "nav:$selectedNavigation"
            if (app.page == selectedNavigation && memory.last[view] == navigation && memory.focusedTargets.singleOrNull() == "$view:$navigation") {
                // Navigation survives the page content change; restoring it would interrupt key repeats.
                memory.restoring = false
                return@LaunchedEffect
            }
            memory.restoring = true
            withFrameNanos { }; withFrameNanos { }
            if (memory.activeDialogs > 0) return@LaunchedEffect
            val songList = when {
                app.playlistDetail != null -> playlistSongs
                app.artistDetail != null -> artistSongs
                app.page in listOf("点歌", "找歌") -> librarySongs
                else -> null
            }
            if (app.selected == null && songList != null && handledSongListReset != app.songListResetVersion) {
                handledSongListReset = app.songListResetVersion
                songList.scrollToItem(0)
                if (app.songListFocusFirst || memory.last[view]?.startsWith("song:") == true) {
                    app.songs.firstOrNull()?.let { memory.last[view] = "song:${it.getString("id")}" }
                }
            }
            if (memory.last[view]?.startsWith("nav:") == true) memory.last[view] = "nav:$selectedNavigation"
            val target = memory.last[view].orEmpty()
            if (app.selected == null) {
                val id = target.substringAfter(':', "")
                if (app.page in listOf("热歌", "历史") && (target.startsWith("popular:") || target.startsWith("history:"))) {
                    val rows = if (app.page == "热歌") app.popularSongs else app.historyRecords
                    val index = rows.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) feedList.scrollToItem(index)
                } else if (songList != null && target.startsWith("song:")) {
                    var index = app.songs.indexOfFirst { it.optString("id") == id }
                    if (index < 0 && app.songs.isNotEmpty()) {
                        index = 0
                        memory.last[view] = "song:${app.songs.first().getString("id")}"
                    }
                    if (index >= 0) {
                        val layout = songList.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null || item.offset < layout.viewportStartOffset || item.offset + item.size > layout.viewportEndOffset) songList.scrollToItem(index)
                    }
                } else {
                    val (state, items) = when {
                        app.page == "歌手" && target.startsWith("artist:") -> artistsGrid to app.artists
                        app.page == "歌单" && target.startsWith("playlist:") -> playlistsGrid to app.playlists
                        app.page == "分类" && target.startsWith("category:") -> categoriesGrid to app.tags.filter { it.optString("dimension") == categoryDimension }
                        else -> artistsGrid to emptyList()
                    }
                    val index = items.indexOfFirst { it.optString("id") == id }
                    if (index >= 0) {
                        val layout = state.layoutInfo
                        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                        if (item == null || item.offset.y < layout.viewportStartOffset || item.offset.y + item.size.height > layout.viewportEndOffset) state.scrollToItem(index)
                    }
                }
            }
            if (app.selected == null && app.page in listOf("热歌", "历史") && handledFeedReset != app.feedResetVersion) {
                handledFeedReset = app.feedResetVersion
                val rows = if (app.page == "热歌") app.popularSongs else app.historyRecords
                val existing = rows.indexOfFirst { it.optString("id") == target.substringAfter(':', "") }
                if (app.feedFocusFirst || (existing < 0 && (target.startsWith("popular:") || target.startsWith("history:")))) {
                    feedList.scrollToItem(0)
                    rows.firstOrNull()?.let { memory.last[view] = "${if (app.page == "热歌") "popular" else "history"}:${it.optString("id")}" }
                }
            }
            withFrameNanos { }
            if (!memory.restore(view) && app.page != "演唱") {
                val default = when {
                    app.selected != null -> "action:加入待唱"
                    app.playlistDetail != null -> app.songs.firstOrNull()?.let { "song:${it.optString("id")}" } ?: "action:返回歌单"
                    app.artistDetail != null -> app.songs.firstOrNull()?.let { "song:${it.optString("id")}" } ?: "action:返回歌手"
                    else -> null
                }
                if (default != null) {
                    memory.last[view] = default
                    memory.targets["$view:$default"]?.requestFocus()
                } else {
                    memory.last[view] = "nav:$selectedNavigation"
                    homeFocus.requestFocus()
                }
            }
            memory.restoring = false
            if (app.page == "演唱") memory.focusedTargets.singleOrNull()?.takeIf { it.startsWith("$view:") }?.let { memory.last[view] = it.removePrefix("$view:") }
        }
    }
    LaunchedEffect(app.connected, app.snapshot.optString("id")) { if (app.connected) app.ensureInvitation() }
    BackHandler(enabled = app.connected) {
        when {
            app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败") -> app.error = ""
            app.selected != null -> app.selected = null
            app.playlistDetail != null -> app.closePlaylist()
            app.artistDetail != null -> app.closeArtist()
            app.page == "演唱" && app.controlVisible -> app.controlVisible = false
            app.page == "找歌" && app.categoryResults -> app.navigate("分类")
            app.page in listOf("找歌", "歌手", "分类", "歌单") -> app.navigate("点歌")
            app.page != "首页" -> app.navigate("首页")
            else -> app.pendingConfirm = "退出演唱"
        }
    }
    Box(Modifier.fillMaxSize().background(Ink).onPreviewKeyEvent {
        // Automatic selection belongs to D-pad focus movement, not platform/editor focus fallback.
        memory.directionalInput = it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
        false
    }) {
        when {
            !app.connected -> TvLoginScreen(app)
            app.page == "演唱" -> PlayerScreen(app)
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeGlyph("brand", Modifier.size(21.dp), Primary)
                        Text("SingVerse", color = Paper, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("首页", "点歌", "已点", "设置").forEach { page ->
                            TvNavigation(if (page == "已点") "已点 ${queuedSongCount(app)}" else page,
                                (if (page == selectedNavigation) Modifier.focusRequester(homeFocus) else Modifier).tvFocusTarget("nav:$page")
                                    .onFocusChanged { if (it.isFocused && memory.directionalInput && memory.activeDialogs == 0) selectNavigation(page) },
                                selected = selectedNavigation == page) { selectNavigation(page) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (app.page != "首页") HomeButton("手机点歌", focusKey = "phone") { invitationOpen = true; app.ensureInvitation() }
                    else StatusPill(if (app.online) "NAS 已连接" else "网络异常", if (app.online) Success else Danger)
                }
                Box(Modifier.weight(1f)) {
                    val catalogPage = app.page in listOf("点歌", "找歌", "歌手", "分类", "歌单")
                    if (app.selected != null) Detail(app)
                    else if (catalogPage) CatalogShell(app) {
                        when {
                            app.playlistDetail != null -> PlaylistDetail(app, playlistSongs)
                            app.artistDetail != null -> ArtistDetail(app, artistSongs)
                            else -> when (app.page) {
                                "点歌", "找歌" -> Library(app, librarySongs)
                                "歌单" -> Playlists(app, playlistsGrid)
                                "歌手" -> Artists(app, artistsGrid)
                                "分类" -> Categories(app, categoriesGrid, categoryDimension) { categoryDimension = it }
                            }
                        }
                    }
                    else when (app.page) {
                        "首页" -> TvHomeScreen(app) { if (!app.online) app.reconnect(); app.ensureInvitation(); invitationOpen = true }
                        "热歌", "历史" -> TvRecommendationScreen(app, feedList)
                        "已点" -> Queue(app)
                        "设置" -> Settings(app)
                    }
                }
                if (app.page != "已点") SingingBar(app)
            }
        }
    }
    if (app.queueAddMessage.isNotBlank() && app.selected == null && app.page != "演唱") {
        Box(Modifier.fillMaxSize().padding(bottom = 80.dp), contentAlignment = Alignment.BottomCenter) {
            Text(app.queueAddMessage, Modifier.widthIn(max = 700.dp).background(Elevated, TvShape.control)
                .padding(horizontal = 20.dp, vertical = 10.dp), color = Paper, fontSize = 14.sp, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (app.error.isNotEmpty() && !app.error.startsWith("与 NAS 同步失败")) Modal("操作提示", "操作没有完成，请根据提示继续。", dismiss = { app.error = "" }) {
        Text(app.error, color = Paper, fontSize = 15.sp, lineHeight = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("关闭提示") { app.error = "" }
            if (app.pendingOperation != null) {
                Action("核对后重试", selected = true) { app.retryPending() }
                Action("放弃本次重试", danger = true) { app.discardPending(); app.error = "" }
            }
        }
    }
    if (invitationOpen) Modal("手机点歌", "微信扫一扫，加入这场聚会。", { invitationOpen = false }, wide = true) {
        if (app.snapshot.optString("status") == "ENDED") {
            EmptyState("本场聚会已结束", "开始新聚会后即可邀请亲友。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("完成") { invitationOpen = false }
                Action("开始新聚会", selected = true) { app.newSession() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(210.dp).background(Paper, TvShape.card).padding(8.dp), contentAlignment = Alignment.Center) {
                    if (app.invitation.isNotEmpty() && app.online) Qr(app.invitation, Modifier.fillMaxSize())
                    else Text(if (app.invitationLoading) "正在生成邀请…" else if (!app.online) "网络异常" else "邀请待刷新", color = Ink, fontSize = 15.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("让亲友自己选一首", color = Paper, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
                    Text("电视与手机连接同一网络。扫码后填写昵称即可点歌，无需安装应用。", color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    StatusPill(if (app.participantsKnown) "已加入 ${app.participantCount} 台设备" else "正在同步设备", Success)
                    if (app.invitationError.isNotBlank()) Text(app.invitationError, color = Danger, fontSize = 13.sp, lineHeight = 19.sp)
                    Text("刷新邀请后，旧二维码立即失效。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("刷新邀请") { app.invite() }
                if (!app.online) Action("重新连接") { app.reconnect() }
            }
            Action("完成") { invitationOpen = false }
        }
    }
    app.pendingConfirm?.let { action -> Modal("${action}？", "此操作会影响当前演唱。", { app.pendingConfirm = null }) {
        Text(if (action == "清空待唱") "所有待唱歌曲将被移除，当前演唱不受影响。" else "请确认后继续。", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("取消") { app.pendingConfirm = null }
            Action("确认", selected = true, danger = action in listOf("散场", "清空待唱", "更换 NAS")) {
                app.pendingConfirm = null
                when (action) {
                    "散场" -> app.command("END")
                    "清空待唱" -> app.command("CLEAR")
                    "退出演唱" -> { app.stopLocal(); app.navigate("首页") }
                    "更换 NAS" -> app.disconnect()
                }
            }
        }
    } }
}

internal fun queuedSongCount(app: TvApp) = app.snapshot.optJSONArray("queue")?.objects().orEmpty().count { it.optString("status") in listOf("WAITING", "HANDOFF", "PLAYING") }

@Composable private fun CatalogShell(app: TvApp, content: @Composable () -> Unit) {
    val selected = if (app.page == "找歌") { if (app.categoryResults) "分类" else "点歌" } else app.page
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(124.dp).fillMaxHeight().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("点歌", Modifier.padding(start = 8.dp, bottom = 8.dp), color = Paper, fontSize = 24.sp, lineHeight = 32.sp)
            listOf("点歌" to "搜索歌曲", "歌手" to "歌手点歌", "分类" to "分类点歌", "歌单" to "共享歌单").forEach { (page, label) ->
                var focused by remember { mutableStateOf(false) }
                Button(onClick = { if (app.page != page || app.categoryResults || app.artistDetail != null || app.playlistDetail != null) app.navigate(page) },
                    modifier = Modifier.fillMaxWidth().height(40.dp).tvFocusTarget("catalog:$page").onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control, 1f),
                    scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.control), contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.colors(containerColor = if (selected == page) Panel else Color.Transparent,
                        focusedContainerColor = Elevated, contentColor = if (selected == page) Paper else Muted, focusedContentColor = Paper)) {
                    Text(label, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Action("刷新曲库", Modifier.fillMaxWidth(), textSize = 12.sp) { app.run { app.loadHome(); app.loadSongs(); app.loadTags(); if (app.page == "歌手") app.loadArtists(); if (app.page == "歌单") app.fetchPlaylists() } }
            if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会", Modifier.fillMaxWidth(), textSize = 12.sp) { app.newSession() }
            Text("${app.homeTotal} 首自备歌曲", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Hairline))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable private fun SingingBar(app: TvApp) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" }
    val next = queue.firstOrNull { it.optString("status") == "HANDOFF" } ?: queue.firstOrNull { it.optString("status") == "WAITING" }
    val song = (current ?: next)?.optJSONObject("song")
    Row(Modifier.fillMaxWidth().height(48.dp).background(Panel, TvShape.card)
        .border(1.dp, Hairline.copy(alpha = .45f), TvShape.card).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SongArtwork(app, song, Modifier.size(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (song == null) "今晚，唱你喜欢的歌" else song.optString("title"), color = Paper, fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                app.snapshot.optString("status") == "ENDED" -> "聚会已结束"
                current != null -> "${song?.artists()} · ${stateLabel(app.playerState)}"
                next != null -> "${song?.artists()} · 等待确认开唱"
                else -> "从曲库选一首，开启家庭音乐现场"
            }, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!app.online) HomeButton("网络异常 · 重连", focusKey = "bar:reconnect") { app.reconnect() }
        HomeButton("已点歌曲 (${queuedSongCount(app)})", focusKey = "bar:queue") { app.navigate("已点") }
        when {
            app.snapshot.optString("status") == "ENDED" -> HomeButton("开始新聚会", primary = true, focusKey = "bar:session") { app.newSession() }
            current == null && next != null -> HomeButton("确认开唱", primary = true, focusKey = "bar:start") { app.command("START") }
            current != null || app.snapshot.optJSONObject("playback")?.optString("songId").orEmpty().isNotEmpty() -> HomeButton("返回演唱", primary = current != null, focusKey = "bar:sing") { app.page = "演唱" }
        }
    }
}

@Composable internal fun Qr(value: String, modifier: Modifier = Modifier.size(190.dp)) {
    BoxWithConstraints(modifier.background(Color.White)) {
        val size = minOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
        val bitmap = remember(value, size) { runCatching {
            val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to "M"))
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
                for (x in 0 until matrix.width) for (y in 0 until matrix.height) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }.getOrNull() }
        if (bitmap != null) Image(bitmap.asImageBitmap(), "本场邀请二维码", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}

@Composable private fun Detail(app: TvApp) {
    val song = app.selected ?: return
    fun field(name: String) = song.optString(name).takeIf { it.isNotBlank() && it != "null" }
    val album = listOfNotNull(field("album"), field("releaseDate")).joinToString(" · ")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("点歌  /  歌曲详情", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(34.dp), verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(app, song, Modifier.size(230.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text(song.optString("title"), color = Paper, fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artists(), color = Paper.copy(alpha = .86f), fontSize = 21.sp, lineHeight = 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (album.isNotEmpty()) Text(album, color = Muted, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (song.optLong("durationMs") > 0) DetailTag(time(song.optLong("durationMs")))
                        DetailTag(versionLabel(song.optString("version")))
                    }
                    field("sourceName")?.let { Text("来源  ·  $it", color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action(if (app.queueAddSongId == song.optString("id")) "加入中…" else "＋ 加入待唱", modifier = Modifier.height(44.dp).width(180.dp), selected = true, focusKey = "加入待唱", textSize = 18.sp) {
                            app.addSongFromList(song, closeDetailOnSuccess = true)
                        }
                        Action("返回", modifier = Modifier.height(44.dp), textSize = 16.sp) { app.selected = null }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailTag(label: String) {
    Text(label, modifier = Modifier.background(Panel, RoundedCornerShape(7.dp)).padding(horizontal = 11.dp, vertical = 6.dp), color = Paper, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable internal fun Queue(app: TvApp, compact: Boolean = false) {
    val queue = app.snapshot.optJSONArray("queue")?.objects().orEmpty()
    val waiting = tvWaitingQueue(queue) { it.optString("status") }
    val current = queue.firstOrNull { it.optString("status") == "PLAYING" } ?: queue.firstOrNull { it.optString("status") == "HANDOFF" }
    var actionItem by remember { mutableStateOf<JSONObject?>(null) }
    var actionIndex by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val memory = LocalTvFocusMemory.current
    val page = LocalTvFocusPage.current
    val queueIds = waiting.map { it.optString("id") }
    LaunchedEffect(queueIds, memory.restoreVersion) {
        val focusedId = memory.last[page]?.takeIf { it.startsWith("action:queue:") }?.removePrefix("action:queue:")
        if (focusedId != null && (memory.dialogs.lastOrNull() == null || memory.dialogs.lastOrNull() == page)) {
            val index = queueIds.indexOf(focusedId)
            if (index >= 0) list.scrollToItem(index)
            else memory.last[page] = waiting.getOrNull(actionIndex.coerceAtMost((waiting.size - 1).coerceAtLeast(0)))?.let { "action:queue:${it.optString("id")}" } ?: "action:去点歌"
            withFrameNanos { }; withFrameNanos { }
            memory.restore(page)
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (compact) SectionTitle("接下来", "${waiting.size} 首待唱") else Heading("待唱队列", "${waiting.size} 首待唱 · 选择歌曲可调整顺序")
            Spacer(Modifier.weight(1f))
            if (current == null && waiting.isNotEmpty()) Action("开始下一首", selected = true) { app.command("START") }
        }
        if (!compact) current?.let { item ->
            val song = item.optJSONObject("song")
            val playback = app.snapshot.optJSONObject("playback")
            val confirmed = playback?.optString("itemId") == item.optString("id") && playback?.optBoolean("confirmed") == true
            Row(Modifier.fillMaxWidth().background(Panel, TvShape.card).border(1.dp, Primary.copy(alpha = .25f), TvShape.card).padding(13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SongArtwork(app, song, Modifier.size(58.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(when {
                        item.optString("status") == "HANDOFF" && !confirmed -> "等待确认开唱"
                        !app.hasConfirmedPlayback -> "准备中"
                        else -> stateLabel(app.playerState)
                    }, Primary)
                    Text(song?.optString("title").orEmpty(), color = Paper, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song?.artists().orEmpty(), color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.optString("status") == "HANDOFF" && !confirmed) Action("确认开唱", selected = true) { app.command("START") }
                else Action("返回演唱") { app.page = "演唱" }
            }
        }
        if (waiting.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState("暂无待唱歌曲", "去点歌，选一首喜欢的歌。")
                Action("去点歌", selected = true) { app.navigate("点歌") }
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(waiting, key = { it.getString("id") }) { item ->
                    QueueRow(app, item, waiting.indexOf(item)) { actionItem = item; actionIndex = waiting.indexOf(item) }
                }
            }
            if (!compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("确认歌曲后选择排序操作", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Action("清空待唱", danger = true) { app.pendingConfirm = "清空待唱" }
            }
        }
    }
    actionItem?.let { original ->
        val item = waiting.firstOrNull { it.optString("id") == original.optString("id") }
        if (item == null) LaunchedEffect(original) { actionItem = null }
        else {
            val index = waiting.indexOf(item)
            val id = item.getString("id")
            Modal("调整待唱", item.optJSONObject("song")?.optString("title").orEmpty(), { actionItem = null }) {
                val actions = buildList {
                    if (index > 0) { add("置顶" to "TOP"); add("上移" to "UP") }
                    if (index < waiting.lastIndex) add("下移" to "DOWN")
                    add("移除" to "REMOVE")
                }
                actions.forEach { (label, command) ->
                    Action(label, Modifier.fillMaxWidth(), focusKey = "queue:$id:$command", danger = command == "REMOVE") {
                        app.command(command, id)
                        actionItem = null
                    }
                }
                Action("完成") { actionItem = null }
            }
        }
    }

}

@Composable private fun QueueRow(app: TvApp, item: JSONObject, index: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val song = item.optJSONObject("song")
    Button(onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).tvFocusTarget("action:queue:${item.optString("id")}")
            .onFocusChanged { focused = it.isFocused }.tvFocus(focused, TvShape.control, 1f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f), shape = ButtonDefaults.shape(TvShape.control),
        colors = ButtonDefaults.colors(containerColor = Panel, focusedContainerColor = Elevated, contentColor = Paper, focusedContentColor = Paper)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", Modifier.width(30.dp), color = Muted, fontSize = 18.sp)
            SongArtwork(app, song, Modifier.size(44.dp))
            Text(song?.optString("title").orEmpty(), Modifier.weight(1.5f), color = Paper, fontSize = 18.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song?.artists().orEmpty(), Modifier.weight(1f), color = Muted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (focused) "确认调整顺序" else "待唱", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun Settings(app: TvApp) {
    var group by remember { mutableStateOf("连接") }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.width(156.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设置", color = Paper, fontSize = 24.sp, lineHeight = 32.sp)
            listOf("连接", "播放设置", "聚会管理", "本场参与者", "设备信息", "应用更新").forEach { label ->
                Action(label, Modifier.fillMaxWidth(), selected = label == group) { group = label }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().background(Panel.copy(alpha = .8f), TvShape.panel)
            .border(1.dp, Hairline.copy(alpha = .4f), TvShape.panel).padding(22.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (group) {
                "连接" -> {
                    Heading(app.serverName, "当前 NAS 与设备连接")
                    Text(app.client.base, color = Primary, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    StatusPill(if (app.online) "连接正常" else "网络异常", if (app.online) Success else Danger)
                    SettingActionRow("检查连接", "重新读取服务身份、聚会和播放状态") { Action("检查连接") { app.connect() } }
                    SettingActionRow("更换 NAS", "忘记这台电视保存的当前设备凭据") { Action("更换 NAS / 忘记连接", danger = true) { app.pendingConfirm = "更换 NAS" } }
                    SettingActionRow("停止本机播放", "只停止这台电视，不替远程交接自动确认") { Action("停止本机播放") { app.stopLocal() } }
                }
                "聚会管理" -> {
                    Heading("聚会管理", if (app.snapshot.optBoolean("acceptingRequests")) "当前允许亲友点歌" else "当前已暂停接单")
                    StatusPill(if (app.snapshot.optString("status") == "ENDED") "聚会已结束" else if (app.snapshot.optBoolean("acceptingRequests")) "接单中" else "已暂停接单",
                        if (app.snapshot.optBoolean("acceptingRequests")) Success else Primary)
                    if (app.snapshot.optString("status") == "ENDED") Action("开始新聚会", selected = true) { app.newSession() }
                    else {
                        SettingActionRow("亲友点歌", "暂停后保留现有队列，不再接收新点歌") {
                            Action(if (app.snapshot.optBoolean("acceptingRequests")) "暂停接单" else "恢复接单") { app.command(if (app.snapshot.optBoolean("acceptingRequests")) "LOCK" else "UNLOCK") }
                        }
                        SettingActionRow("结束这场聚会", "停止接单并使邀请与参与授权失效") { Action("结束聚会", danger = true) { app.pendingConfirm = "散场" } }
                    }
                }
                "播放设置" -> PlaybackSettings(app)
                "应用更新" -> {
                    Heading("应用更新", app.updateStatus)
                    Text("当前版本  ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    SettingActionRow("NAS 发布包", "检查、下载并核对包名、摘要、版本与签名") { Action("检查更新", selected = true) { app.checkUpdate() } }
                    if (app.updateManifest?.optBoolean("available") == true) Action(if (app.busy) "处理中…" else "下载并校验", enabled = !app.busy) { if (!app.busy) app.downloadUpdate() }
                    if (app.updateFile != null) Action("确认安装 / 开启安装权限后重试", selected = true) { app.installUpdate() }
                }
                "设备信息" -> {
                    Heading("设备信息", "用于确认播放终端与系统环境")
                    InfoRow("设备型号", android.os.Build.MODEL)
                    InfoRow("Android", android.os.Build.VERSION.RELEASE)
                    InfoRow("应用", "SingVerse TV ${BuildConfig.VERSION_NAME}")
                    InfoRow("播放", "Media3 · 直放 / 兼容播放")
                }
                else -> {
                    Heading("本场参与者", "主持权限只在电视上授予；撤销后可重新扫码加入。")
                    if (app.participants.isEmpty()) EmptyState("还没有亲友加入", "通过「手机点歌」邀请亲友。")
                    app.participants.forEach { participant ->
                        Row(Modifier.fillMaxWidth().background(Elevated.copy(alpha = .55f), TvShape.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(participant.optString("name"), color = Paper, fontSize = 17.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (participant.optBoolean("revoked")) "已撤销" else "已加入本场", color = if (participant.optBoolean("revoked")) Muted else Success, fontSize = 12.sp)
                            }
                            Action("授予主持", focusKey = "host:${participant.getString("id")}") { app.command("HOST", participantId = participant.getString("id")) }
                            Spacer(Modifier.width(8.dp))
                            Action("撤销访问", focusKey = "revoke:${participant.getString("id")}", danger = true) { app.command("REVOKE", participantId = participant.getString("id")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SettingActionRow(title: String, description: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Elevated.copy(alpha = .42f), TvShape.card).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Paper, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        action()
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(110.dp), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        Text(value, color = Paper, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
